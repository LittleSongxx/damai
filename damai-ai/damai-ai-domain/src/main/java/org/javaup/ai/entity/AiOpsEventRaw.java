package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("d_ai_ops_event_raw")
public class AiOpsEventRaw extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
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
    private String eventStatus;
    private Date occurredAt;
    private String payloadJson;
    private String operatorId;
    private String extJson;
}
