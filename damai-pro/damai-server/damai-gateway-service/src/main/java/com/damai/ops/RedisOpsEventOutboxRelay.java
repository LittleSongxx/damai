package com.damai.ops;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class RedisOpsEventOutboxRelay {

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final OpsEventRabbitMqConfig config;

    public RedisOpsEventOutboxRelay(@Qualifier("redisToolStringRedisTemplate") StringRedisTemplate redisTemplate,
                                    RabbitTemplate rabbitTemplate,
                                    OpsEventRabbitMqConfig config) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${damai.rabbitmq.ops.outbox-relay-delay-ms:5000}")
    public void publishDueEvents() {
        Set<String> eventIds = redisTemplate.opsForZSet()
                .rangeByScore(RedisOpsEventOutboxService.DUE_ZSET, 0, System.currentTimeMillis(), 0, 100);
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        for (String eventId : eventIds) {
            publishOne(eventId);
        }
    }

    private void publishOne(String eventId) {
        Object raw = redisTemplate.opsForHash().get(RedisOpsEventOutboxService.RECORD_HASH, eventId);
        if (raw == null) {
            redisTemplate.opsForZSet().remove(RedisOpsEventOutboxService.DUE_ZSET, eventId);
            return;
        }
        RedisOpsEventOutboxRecord record = JSON.parseObject(String.valueOf(raw), RedisOpsEventOutboxRecord.class);
        try {
            rabbitTemplate.convertAndSend(config.exchange(), record.getRoutingKey(),
                    record.getEventJson(), new CorrelationData(record.getEventId()));
            redisTemplate.opsForHash().delete(RedisOpsEventOutboxService.RECORD_HASH, eventId);
            redisTemplate.opsForZSet().remove(RedisOpsEventOutboxService.DUE_ZSET, eventId);
        } catch (Exception ex) {
            int retryCount = record.getRetryCount() == null ? 0 : record.getRetryCount() + 1;
            record.setRetryCount(retryCount);
            record.setLastError(ex.getMessage());
            long nextRetryAt = System.currentTimeMillis() + Math.min(300_000L, 1000L * (1L << Math.min(retryCount, 8)));
            redisTemplate.opsForHash().put(RedisOpsEventOutboxService.RECORD_HASH, eventId, JSON.toJSONString(record));
            redisTemplate.opsForZSet().add(RedisOpsEventOutboxService.DUE_ZSET, eventId, nextRetryAt);
            log.warn("gateway ops outbox publish failed, eventId={}, routingKey={}, retryCount={}, message={}",
                    eventId, record.getRoutingKey(), retryCount, ex.getMessage());
        }
    }
}
