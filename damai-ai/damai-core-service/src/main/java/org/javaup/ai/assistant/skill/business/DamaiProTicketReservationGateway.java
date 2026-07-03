package org.javaup.ai.assistant.skill.business;

import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.DaMaiRequestAuthSupport;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.enums.BaseCode;
import org.javaup.ai.vo.result.base.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.Objects;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DamaiProTicketReservationGateway implements TicketReservationGateway {

    private final DaMaiRequestAuthSupport requestAuthSupport;

    @Value("${damai.pro.ai-reservation.base-url:http://127.0.0.1:6085/damai/program/ai/reservations}")
    private String reservationBaseUrl;

    @Value("${damai.pro.ai-reservation.ttl-seconds:900}")
    private Integer ttlSeconds;

    @Value("${damai.pro.ai-reservation.connect-timeout-ms:3000}")
    private Integer connectTimeoutMs;

    @Value("${damai.pro.ai-reservation.read-timeout-ms:8000}")
    private Integer readTimeoutMs;

    @Value("${damai.pro.ai-reservation.max-retries:1}")
    private Integer maxRetries;

    @Override
    public TicketReservation reserve(PurchaseActionSnapshot snapshot, String idempotencyKey, String runId, String actionId) {
        if (snapshot == null || snapshot.getProgramOrderCreateDto() == null || !StringUtils.hasText(idempotencyKey)) {
            throw deterministic("库存预留参数缺失");
        }
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setOrderCreate(snapshot.getProgramOrderCreateDto());
        request.setIdempotencyKey(idempotencyKey);
        request.setTtlSeconds(ttlSeconds);
        request.setSourceRunId(runId);
        request.setSourceActionId(actionId);
        ReservationData data = executeWithRetry(() ->
                post(reservationBaseUrl, request, idempotencyKey, ReservationResponse.class).getData(), true);
        if (data == null || !StringUtils.hasText(data.getReservationId())) {
            throw unknown("damai-pro 未返回有效 reservation");
        }
        return toReservation(data);
    }

    @Override
    public String confirm(PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey) {
        if (reservation == null || !StringUtils.hasText(reservation.reservationId())) {
            throw deterministic("确认订单缺少 reservation");
        }
        ReservationConfirmRequest request = new ReservationConfirmRequest();
        request.setIdempotencyKey(idempotencyKey);
        ReservationData data;
        try {
            data = post(reservationBaseUrl + "/" + reservation.reservationId() + "/confirm",
                    request, idempotencyKey, ReservationResponse.class).getData();
        } catch (ReservationGatewayException ex) {
            if (ex.failureCategory() == FailureCategory.TRANSIENT) {
                throw unknown("damai-pro confirm 返回非确定性错误，需要查询 reservation 状态: " + ex.getMessage(), ex);
            }
            throw ex;
        }
        if (data == null || !StringUtils.hasText(data.getConfirmedOrderNumber())) {
            throw unknown("damai-pro 未返回确认订单号");
        }
        return data.getConfirmedOrderNumber();
    }

    @Override
    public void release(String reservationId, String reason) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        ReservationReleaseRequest request = new ReservationReleaseRequest();
        request.setReason(reason);
        executeWithRetry(() -> post(reservationBaseUrl + "/" + reservationId + "/release",
                request, reservationId + ":release", ReservationResponse.class), true);
    }

    @Override
    public TicketReservation status(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            throw deterministic("查询 reservation 状态缺少 reservationId");
        }
        ReservationData data = executeWithRetry(() -> {
            HttpRequest httpRequest = requestAuthSupport.apply(HttpRequest.get(reservationBaseUrl + "/" + reservationId));
            String result = httpRequest
                    .setConnectionTimeout(connectTimeoutMs == null ? 3000 : connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs == null ? 8000 : readTimeoutMs)
                    .execute()
                    .body();
            ReservationResponse response = JSON.parseObject(result, ReservationResponse.class);
            if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
                throw fromResponse(response);
            }
            return response.getData();
        }, false);
        return toReservation(data);
    }

    private <T> T executeWithRetry(Supplier<T> supplier, boolean idempotentSideEffect) {
        int attempts = Math.max(1, (maxRetries == null ? 1 : maxRetries) + 1);
        ReservationGatewayException last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return supplier.get();
            } catch (ReservationGatewayException ex) {
                last = ex;
                if (!ex.retriable() || (!idempotentSideEffect && ex.unknownResult()) || i == attempts) {
                    throw ex;
                }
                sleepBackoff(i);
            } catch (RuntimeException ex) {
                ReservationGatewayException converted = fromThrowable(ex);
                last = converted;
                if (!converted.retriable() || (!idempotentSideEffect && converted.unknownResult()) || i == attempts) {
                    throw converted;
                }
                sleepBackoff(i);
            }
        }
        throw last == null ? transientFailure("调用 damai-pro AI reservation 失败") : last;
    }

    private <T extends ApiResponse> T post(String url, Object body, String idempotencyKey, Class<T> type) {
        try {
            HttpRequest httpRequest = requestAuthSupport.apply(HttpRequest.post(url));
            if (StringUtils.hasText(idempotencyKey)) {
                httpRequest.header("X-Idempotency-Key", idempotencyKey);
                httpRequest.header("X-AI-Request-Id", idempotencyKey);
            }
            String result = httpRequest.body(JSON.toJSONString(body))
                    .setConnectionTimeout(connectTimeoutMs == null ? 3000 : connectTimeoutMs)
                    .setReadTimeout(readTimeoutMs == null ? 8000 : readTimeoutMs)
                    .execute()
                    .body();
            T response = JSON.parseObject(result, type);
            if (response == null || !Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
                throw fromResponse(response);
            }
            return response;
        } catch (ReservationGatewayException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw fromThrowable(ex);
        }
    }

    private ReservationGatewayException fromResponse(ApiResponse response) {
        String message = response == null || response.getMessage() == null ? "unknown error" : response.getMessage();
        Integer code = response == null ? null : response.getCode();
        if (code != null && code >= 500) {
            return transientFailure("调用 damai-pro AI reservation 失败: " + message);
        }
        return deterministic("调用 damai-pro AI reservation 失败: " + message);
    }

    private ReservationGatewayException fromThrowable(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return unknown("调用 damai-pro AI reservation 超时: " + message, ex);
            }
            cause = cause.getCause();
        }
        String lower = message.toLowerCase();
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect")
                || lower.contains("connection") || lower.contains("read timed out")) {
            return unknown("调用 damai-pro AI reservation 结果未知: " + message, ex);
        }
        return transientFailure("调用 damai-pro AI reservation 失败: " + message, ex);
    }

    private TicketReservation toReservation(ReservationData data) {
        if (data == null) {
            throw unknown("damai-pro 未返回 reservation 数据");
        }
        String status = data.getReservationStatus();
        return new TicketReservation(
                data.getReservationId(),
                data.getExpiresAt(),
                "RESERVED".equals(status),
                data.getMessage(),
                status,
                data.getConfirmedOrderNumber(),
                data.getFailureCategory(),
                Boolean.TRUE.equals(data.getRetriable()),
                Boolean.TRUE.equals(data.getUnknownResult()),
                data.getNextCheckAfterMs());
    }

    private ReservationGatewayException deterministic(String message) {
        return new ReservationGatewayException(message, FailureCategory.DETERMINISTIC, false, false);
    }

    private ReservationGatewayException transientFailure(String message) {
        return new ReservationGatewayException(message, FailureCategory.TRANSIENT, true, false);
    }

    private ReservationGatewayException transientFailure(String message, Throwable cause) {
        return new ReservationGatewayException(message, cause, FailureCategory.TRANSIENT, true, false);
    }

    private ReservationGatewayException unknown(String message) {
        return new ReservationGatewayException(message, FailureCategory.SIDE_EFFECT_UNKNOWN, true, true);
    }

    private ReservationGatewayException unknown(String message, Throwable cause) {
        return new ReservationGatewayException(message, cause, FailureCategory.SIDE_EFFECT_UNKNOWN, true, true);
    }

    private void sleepBackoff(int attempt) {
        try {
            long delayMs = Math.min(2000L, 100L * (1L << Math.min(attempt, 5))) + (long) (Math.random() * 100L);
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw transientFailure("调用 damai-pro AI reservation 重试被中断", ex);
        }
    }

    @Data
    private static class ReservationCreateRequest {
        private ProgramOrderCreateDto orderCreate;
        private String idempotencyKey;
        private String sourceRunId;
        private String sourceActionId;
        private Integer ttlSeconds;
    }

    @Data
    private static class ReservationConfirmRequest {
        private String idempotencyKey;
    }

    @Data
    private static class ReservationReleaseRequest {
        private String reason;
    }

    @Data
    private static class ReservationResponse extends ApiResponse {
        private ReservationData data;
    }

    @Data
    private static class ReservationData {
        private String reservationId;
        private String reservationStatus;
        private Date expiresAt;
        private String confirmedOrderNumber;
        private String message;
        private String failureCategory;
        private String sagaStatus;
        private Boolean retriable;
        private Boolean unknownResult;
        private Long nextCheckAfterMs;
        private String lastError;
    }
}
