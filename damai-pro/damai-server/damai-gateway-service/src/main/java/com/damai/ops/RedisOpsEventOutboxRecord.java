package com.damai.ops;

import lombok.Data;

@Data
public class RedisOpsEventOutboxRecord {

    private String eventId;

    private String routingKey;

    private String eventType;

    private String eventJson;

    private Integer retryCount;

    private String lastError;
}
