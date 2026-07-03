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

import java.util.Date;
import java.util.Objects;

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

    @Override
    public TicketReservation reserve(PurchaseActionSnapshot snapshot, String idempotencyKey, String runId, String actionId) {
        if (snapshot == null || snapshot.getProgramOrderCreateDto() == null || !StringUtils.hasText(idempotencyKey)) {
            throw new RuntimeException("库存预留参数缺失");
        }
        ReservationCreateRequest request = new ReservationCreateRequest();
        request.setOrderCreate(snapshot.getProgramOrderCreateDto());
        request.setIdempotencyKey(idempotencyKey);
        request.setTtlSeconds(ttlSeconds);
        request.setSourceRunId(runId);
        request.setSourceActionId(actionId);
        ReservationResponse response = post(reservationBaseUrl, request, idempotencyKey, ReservationResponse.class);
        ReservationData data = response.getData();
        if (data == null || !StringUtils.hasText(data.getReservationId())) {
            throw new RuntimeException("damai-pro 未返回有效 reservation");
        }
        return new TicketReservation(data.getReservationId(), data.getExpiresAt(), "RESERVED".equals(data.getReservationStatus()),
                data.getMessage());
    }

    @Override
    public String confirm(PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey) {
        if (reservation == null || !StringUtils.hasText(reservation.reservationId())) {
            throw new RuntimeException("确认订单缺少 reservation");
        }
        ReservationConfirmRequest request = new ReservationConfirmRequest();
        request.setIdempotencyKey(idempotencyKey);
        ReservationResponse response = post(reservationBaseUrl + "/" + reservation.reservationId() + "/confirm",
                request, idempotencyKey, ReservationResponse.class);
        ReservationData data = response.getData();
        if (data == null || !StringUtils.hasText(data.getConfirmedOrderNumber())) {
            throw new RuntimeException("damai-pro 未返回确认订单号");
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
        post(reservationBaseUrl + "/" + reservationId + "/release",
                request, reservationId + ":release", ReservationResponse.class);
    }

    private <T extends ApiResponse> T post(String url, Object body, String idempotencyKey, Class<T> type) {
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
            String message = response == null || response.getMessage() == null ? "unknown error" : response.getMessage();
            throw new RuntimeException("调用 damai-pro AI reservation 失败: " + message);
        }
        return response;
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
    }
}
