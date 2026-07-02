package com.damai.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baidu.fsg.uid.UidGenerator;
import com.damai.constant.Constant;
import com.damai.domain.OpsEvent;
import com.damai.domain.PurchaseSeat;
import com.damai.dto.AiReservationCreateDto;
import com.damai.dto.ProgramOrderCreateDto;
import com.damai.entity.AiReservation;
import com.damai.enums.BaseCode;
import com.damai.enums.ProgramOrderVersion;
import com.damai.exception.DaMaiFrameException;
import com.damai.mapper.AiReservationMapper;
import com.damai.service.ops.OpsEventPublisher;
import com.damai.service.domain.CreateOrderTemporaryData;
import com.damai.threadlocal.BaseParameterHolder;
import com.damai.util.DateUtils;
import com.damai.vo.AiReservationVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReservationService {

    private static final int DEFAULT_TTL_SECONDS = 15 * 60;
    private static final String RESERVED = "RESERVED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String RELEASED = "RELEASED";
    private static final String EXPIRED = "EXPIRED";

    private final AiReservationMapper aiReservationMapper;
    private final ProgramOrderService programOrderService;
    private final ProgramOrderIdempotencyService idempotencyService;
    private final UidGenerator uidGenerator;
    private final OpsEventPublisher opsEventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public AiReservationVo reserve(AiReservationCreateDto request) {
        ProgramOrderCreateDto orderCreate = request == null ? null : request.getOrderCreate();
        if (orderCreate == null || orderCreate.getProgramId() == null || orderCreate.getUserId() == null) {
            throw new DaMaiFrameException(BaseCode.PARAMETER_ERROR.getCode(), "AI reservation 参数缺失");
        }
        String idempotencyKey = normalizeIdempotencyKey(request.getIdempotencyKey());
        AiReservation existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return toVo(existing, "reservation 已存在");
        }
        AiReservation active = findActiveSameTicket(orderCreate);
        if (active != null) {
            throw new DaMaiFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER.getCode(),
                    "同一用户同一节目票档已有未完成 AI 预留，请先确认或释放");
        }

        CreateOrderTemporaryData temporaryData = programOrderService.reserveForAi(orderCreate);
        Date now = DateUtils.now();
        Date expiresAt = new Date(now.getTime() + ttlSeconds(request) * 1000L);
        AiReservation reservation = new AiReservation();
        reservation.setId(uidGenerator.getUid());
        reservation.setReservationId("air_" + uidGenerator.getUid());
        reservation.setUserId(orderCreate.getUserId());
        reservation.setProgramId(orderCreate.getProgramId());
        reservation.setTicketCategoryId(resolveTicketCategoryId(orderCreate, temporaryData.getPurchaseSeatList()));
        reservation.setTicketCount(resolveTicketCount(orderCreate, temporaryData.getPurchaseSeatList()));
        reservation.setTicketUserIdsJson(JSON.toJSONString(orderCreate.getTicketUserIdList()));
        reservation.setPurchaseSeatsJson(JSON.toJSONString(temporaryData.getPurchaseSeatList()));
        reservation.setIdentifierId(temporaryData.getIdentifierId());
        reservation.setReservationStatus(RESERVED);
        reservation.setExpiresAt(expiresAt);
        reservation.setIdempotencyKey(idempotencyKey);
        reservation.setSourceRunId(request.getSourceRunId());
        reservation.setSourceActionId(request.getSourceActionId());
        reservation.setCreateTime(now);
        reservation.setEditTime(now);
        reservation.setStatus(1);
        aiReservationMapper.insert(reservation);
        publishReservationEvent("AI_RESERVATION_CREATED", "ops.reservation.created", reservation, null);
        return toVo(reservation, "reservation 已创建，库存已锁定");
    }

    @Transactional(rollbackFor = Exception.class)
    public AiReservationVo confirm(String reservationId, String idempotencyKey) {
        AiReservation reservation = getRequired(reservationId);
        if (CONFIRMED.equals(reservation.getReservationStatus())) {
            return toVo(reservation, "订单已创建");
        }
        if (!RESERVED.equals(reservation.getReservationStatus())) {
            throw new DaMaiFrameException(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT.getCode(),
                    "reservation 当前状态不可确认: " + reservation.getReservationStatus());
        }
        if (reservation.getExpiresAt() != null && reservation.getExpiresAt().before(DateUtils.now())) {
            expireOne(reservation, "EXPIRED_BEFORE_CONFIRM");
            throw new DaMaiFrameException(BaseCode.OPERATION_IS_TOO_FREQUENT_PLEASE_TRY_AGAIN_LATER.getCode(),
                    "reservation 已过期，请重新发起购票预览");
        }
        ProgramOrderCreateDto orderCreate = rebuildOrderCreate(reservation);
        List<PurchaseSeat> purchaseSeats = parsePurchaseSeats(reservation);
        String orderNumber = idempotencyService.execute(normalizeIdempotencyKey(idempotencyKey), orderCreate,
                () -> programOrderService.createReservedOrder(orderCreate, purchaseSeats, ProgramOrderVersion.V4_VERSION.getValue()));
        reservation.setReservationStatus(CONFIRMED);
        reservation.setConfirmedOrderNumber(orderNumber);
        reservation.setEditTime(DateUtils.now());
        aiReservationMapper.updateById(reservation);
        publishReservationEvent("AI_RESERVATION_CONFIRMED", "ops.reservation.confirmed", reservation, orderNumber);
        return toVo(reservation, "订单已创建");
    }

    @Transactional(rollbackFor = Exception.class)
    public AiReservationVo release(String reservationId, String reason) {
        AiReservation reservation = getRequired(reservationId);
        if (CONFIRMED.equals(reservation.getReservationStatus())) {
            return toVo(reservation, "订单已确认，无需释放");
        }
        if (RELEASED.equals(reservation.getReservationStatus()) || EXPIRED.equals(reservation.getReservationStatus())) {
            return toVo(reservation, "reservation 已释放");
        }
        releaseLockedSeats(reservation);
        reservation.setReservationStatus(RELEASED);
        reservation.setReleasedAt(DateUtils.now());
        reservation.setReleaseReason(StringUtils.hasText(reason) ? reason : "AI_RELEASE");
        reservation.setEditTime(DateUtils.now());
        aiReservationMapper.updateById(reservation);
        publishReservationEvent("AI_RESERVATION_RELEASED", "ops.reservation.released", reservation, null);
        return toVo(reservation, "reservation 已释放");
    }

    public AiReservationVo status(String reservationId) {
        return toVo(getRequired(reservationId), "OK");
    }

    @Scheduled(fixedDelay = 60000L)
    @Transactional(rollbackFor = Exception.class)
    public void expireDueReservations() {
        List<AiReservation> due = aiReservationMapper.selectList(Wrappers.lambdaQuery(AiReservation.class)
                .eq(AiReservation::getReservationStatus, RESERVED)
                .eq(AiReservation::getStatus, 1)
                .lt(AiReservation::getExpiresAt, DateUtils.now())
                .last("limit 100"));
        for (AiReservation reservation : due) {
            try {
                expireOne(reservation, "TTL_EXPIRED");
            } catch (Exception ex) {
                log.warn("AI reservation expire failed, reservationId={}, message={}",
                        reservation.getReservationId(), ex.getMessage());
            }
        }
    }

    private void expireOne(AiReservation reservation, String reason) {
        releaseLockedSeats(reservation);
        reservation.setReservationStatus(EXPIRED);
        reservation.setReleasedAt(DateUtils.now());
        reservation.setReleaseReason(reason);
        reservation.setEditTime(DateUtils.now());
        aiReservationMapper.updateById(reservation);
        publishReservationEvent("AI_RESERVATION_EXPIRED", "ops.reservation.expired", reservation, null);
    }

    private void releaseLockedSeats(AiReservation reservation) {
        programOrderService.releaseReservedSeats(reservation.getProgramId(), parsePurchaseSeats(reservation));
    }

    private AiReservation findActiveSameTicket(ProgramOrderCreateDto orderCreate) {
        AiReservation reservation = aiReservationMapper.selectOne(Wrappers.lambdaQuery(AiReservation.class)
                .eq(AiReservation::getUserId, orderCreate.getUserId())
                .eq(AiReservation::getProgramId, orderCreate.getProgramId())
                .eq(AiReservation::getTicketCategoryId, orderCreate.getTicketCategoryId())
                .eq(AiReservation::getReservationStatus, RESERVED)
                .eq(AiReservation::getStatus, 1)
                .gt(AiReservation::getExpiresAt, DateUtils.now())
                .last("limit 1"));
        return reservation;
    }

    private AiReservation findByIdempotencyKey(String idempotencyKey) {
        return aiReservationMapper.selectOne(Wrappers.lambdaQuery(AiReservation.class)
                .eq(AiReservation::getIdempotencyKey, idempotencyKey)
                .eq(AiReservation::getStatus, 1)
                .last("limit 1"));
    }

    private AiReservation getRequired(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            throw new DaMaiFrameException(BaseCode.PARAMETER_ERROR.getCode(), "reservationId 不能为空");
        }
        AiReservation reservation = aiReservationMapper.selectOne(Wrappers.lambdaQuery(AiReservation.class)
                .eq(AiReservation::getReservationId, reservationId)
                .eq(AiReservation::getStatus, 1)
                .last("limit 1"));
        if (reservation == null) {
            throw new DaMaiFrameException(BaseCode.ORDER_NOT_EXIST.getCode(), "AI reservation 不存在");
        }
        return reservation;
    }

    private ProgramOrderCreateDto rebuildOrderCreate(AiReservation reservation) {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setProgramId(reservation.getProgramId());
        dto.setUserId(reservation.getUserId());
        dto.setTicketCategoryId(reservation.getTicketCategoryId());
        dto.setTicketCount(reservation.getTicketCount());
        dto.setTicketUserIdList(JSON.parseArray(reservation.getTicketUserIdsJson(), Long.class));
        return dto;
    }

    private List<PurchaseSeat> parsePurchaseSeats(AiReservation reservation) {
        return JSON.parseArray(reservation.getPurchaseSeatsJson(), PurchaseSeat.class);
    }

    private Long resolveTicketCategoryId(ProgramOrderCreateDto orderCreate, List<PurchaseSeat> purchaseSeats) {
        if (orderCreate.getTicketCategoryId() != null) {
            return orderCreate.getTicketCategoryId();
        }
        return purchaseSeats.stream()
                .map(PurchaseSeat::getTicketCategoryId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new DaMaiFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2));
    }

    private Integer resolveTicketCount(ProgramOrderCreateDto orderCreate, List<PurchaseSeat> purchaseSeats) {
        if (orderCreate.getTicketCount() != null) {
            return orderCreate.getTicketCount();
        }
        return purchaseSeats == null ? 0 : purchaseSeats.size();
    }

    private int ttlSeconds(AiReservationCreateDto request) {
        Integer ttlSeconds = request.getTtlSeconds();
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return DEFAULT_TTL_SECONDS;
        }
        return (int) Math.min(TimeUnit.MINUTES.toSeconds(30), ttlSeconds);
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        return StringUtils.hasText(idempotencyKey)
                ? idempotencyKey
                : "ai-reservation-" + UUID.randomUUID().toString().replace("-", "");
    }

    private AiReservationVo toVo(AiReservation reservation, String message) {
        AiReservationVo vo = new AiReservationVo();
        vo.setReservationId(reservation.getReservationId());
        vo.setReservationStatus(reservation.getReservationStatus());
        vo.setExpiresAt(reservation.getExpiresAt());
        vo.setConfirmedOrderNumber(reservation.getConfirmedOrderNumber());
        vo.setMessage(message);
        return vo;
    }

    private void publishReservationEvent(String eventType, String routingKey, AiReservation reservation, String orderNumber) {
        opsEventPublisher.publish(routingKey, OpsEvent.of(eventType, "damai-program-service")
                .withTrace(BaseParameterHolder.getParameter(Constant.TRACE_ID))
                .withUserId(reservation.getUserId())
                .withProgramId(reservation.getProgramId())
                .withReservationId(reservation.getReservationId())
                .withOrderNumber(orderNumber)
                .withCount(reservation.getTicketCount())
                .withStatus(reservation.getReservationStatus())
                .putPayload("ticketCategoryId", reservation.getTicketCategoryId())
                .putPayload("sourceRunId", reservation.getSourceRunId())
                .putPayload("sourceActionId", reservation.getSourceActionId())
                .putPayload("expiresAt", reservation.getExpiresAt()));
    }
}
