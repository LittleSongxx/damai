package com.damai.service.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class ProgramOrderIdempotencyRecord implements Serializable {

    private String idempotencyKey;

    private String requestHash;

    private String status;

    private String orderNumber;

    private String message;

    private Date updatedAt;
}
