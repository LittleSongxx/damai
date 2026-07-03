package com.damai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.damai.data.BaseTableData;
import lombok.Data;

import java.util.Date;

@Data
@TableName("d_ops_event_outbox")
public class OpsEventOutbox extends BaseTableData {

    private Long id;

    private String eventId;

    private String routingKey;

    private String eventType;

    private String eventJson;

    private String publishStatus;

    private Integer retryCount;

    private Date nextRetryAt;

    private Date publishedAt;

    private String lastError;
}
