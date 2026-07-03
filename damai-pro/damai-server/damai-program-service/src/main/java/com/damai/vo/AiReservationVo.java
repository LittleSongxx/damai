package com.damai.vo;

import lombok.Data;

import java.util.Date;

@Data
public class AiReservationVo {

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
