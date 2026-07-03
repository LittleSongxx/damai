package com.damai.ops;

import com.alibaba.fastjson.JSON;
import com.damai.domain.OpsEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisOpsEventOutboxService {

    static final String RECORD_HASH = "damai:ops:event:outbox:records";
    static final String DUE_ZSET = "damai:ops:event:outbox:due";

    private final StringRedisTemplate redisTemplate;

    public RedisOpsEventOutboxService(@Qualifier("redisToolStringRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void enqueue(String routingKey, OpsEvent event) {
        if (event == null || event.getEventId() == null) {
            return;
        }
        RedisOpsEventOutboxRecord record = new RedisOpsEventOutboxRecord();
        record.setEventId(event.getEventId());
        record.setRoutingKey(routingKey);
        record.setEventType(event.getEventType());
        record.setEventJson(JSON.toJSONString(event));
        record.setRetryCount(0);
        Boolean inserted = redisTemplate.opsForHash()
                .putIfAbsent(RECORD_HASH, event.getEventId(), JSON.toJSONString(record));
        if (Boolean.TRUE.equals(inserted)) {
            redisTemplate.opsForZSet().add(DUE_ZSET, event.getEventId(), System.currentTimeMillis());
        }
    }
}
