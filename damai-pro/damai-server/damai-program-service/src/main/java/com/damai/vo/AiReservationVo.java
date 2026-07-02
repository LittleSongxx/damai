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
}
