package com.damai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.damai.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
@TableName("d_program_ai_reservation")
public class AiReservation extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String reservationId;

    private Long userId;

    private Long programId;

    private Long ticketCategoryId;

    private Integer ticketCount;

    private String ticketUserIdsJson;

    private String purchaseSeatsJson;

    private Long identifierId;

    private String reservationStatus;

    private Date expiresAt;

    private String confirmedOrderNumber;

    private String idempotencyKey;

    private String requestHash;

    private String confirmIdempotencyKey;

    private String failureCategory;

    private String sagaStatus;

    private String lastError;

    private Integer retryCount;

    private String sourceRunId;

    private String sourceActionId;

    private Date releasedAt;

    private String releaseReason;
}
