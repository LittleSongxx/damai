package com.damai.service.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.damai.entity.OpsEventOutbox;
import com.damai.mapper.OpsEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpsEventOutboxRelay {

    private static final String PENDING = "PENDING";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String FAILED = "FAILED";

    private final OpsEventOutboxMapper mapper;
    private final RabbitTemplate rabbitTemplate;
    private final OpsEventRabbitMqConfig config;

    @Scheduled(fixedDelayString = "${damai.rabbitmq.ops.outbox-relay-delay-ms:5000}")
    public void publishDueEvents() {
        List<OpsEventOutbox> rows = mapper.selectList(Wrappers.lambdaQuery(OpsEventOutbox.class)
                .eq(OpsEventOutbox::getStatus, 1)
                .in(OpsEventOutbox::getPublishStatus, List.of(PENDING, FAILED))
                .le(OpsEventOutbox::getNextRetryAt, new Date())
                .orderByAsc(OpsEventOutbox::getId)
                .last("limit 100"));
        for (OpsEventOutbox row : rows) {
            publishOne(row);
        }
    }

    private void publishOne(OpsEventOutbox row) {
        try {
            rabbitTemplate.convertAndSend(config.exchange(), row.getRoutingKey(),
                    row.getEventJson(), new CorrelationData(row.getEventId()));
            row.setPublishStatus(PUBLISHED);
            row.setPublishedAt(new Date());
            row.setLastError(null);
            row.setEditTime(new Date());
            mapper.updateById(row);
        } catch (Exception ex) {
            int retryCount = row.getRetryCount() == null ? 0 : row.getRetryCount() + 1;
            row.setPublishStatus(FAILED);
            row.setRetryCount(retryCount);
            row.setLastError(ex.getMessage());
            row.setNextRetryAt(new Date(System.currentTimeMillis() + Math.min(300_000L, 1000L * (1L << Math.min(retryCount, 8)))));
            row.setEditTime(new Date());
            mapper.updateById(row);
            log.warn("ops outbox publish failed, eventId={}, routingKey={}, retryCount={}, message={}",
                    row.getEventId(), row.getRoutingKey(), retryCount, ex.getMessage());
        }
    }
}
