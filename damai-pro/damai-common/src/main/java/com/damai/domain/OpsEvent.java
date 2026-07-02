package com.damai.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class OpsEvent {

    private String eventId = UUID.randomUUID().toString();
    private String eventType;
    private String sourceService;
    private String traceId;
    private String spanId;
    private Long userId;
    private Long programId;
    private String orderNumber;
    private String reservationId;
    private BigDecimal amount;
    private Integer count;
    private String status;
    private String occurredAt = Instant.now().toString();
    private Map<String, Object> payloadJson = new LinkedHashMap<>();

    public static OpsEvent of(String eventType, String sourceService) {
        OpsEvent event = new OpsEvent();
        event.setEventType(eventType);
        event.setSourceService(sourceService);
        return event;
    }

    public OpsEvent withTrace(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public OpsEvent withUserId(Long userId) {
        this.userId = userId;
        return this;
    }

    public OpsEvent withProgramId(Long programId) {
        this.programId = programId;
        return this;
    }

    public OpsEvent withOrderNumber(Object orderNumber) {
        this.orderNumber = orderNumber == null ? null : String.valueOf(orderNumber);
        return this;
    }

    public OpsEvent withReservationId(String reservationId) {
        this.reservationId = reservationId;
        return this;
    }

    public OpsEvent withAmount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public OpsEvent withCount(Integer count) {
        this.count = count;
        return this;
    }

    public OpsEvent withStatus(String status) {
        this.status = status;
        return this;
    }

    public OpsEvent putPayload(String key, Object value) {
        this.payloadJson.put(key, value);
        return this;
    }
}
