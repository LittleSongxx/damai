package com.damai.ops;

import com.alibaba.fastjson.JSON;
import com.damai.domain.OpsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpsEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final OpsEventRabbitMqConfig opsEventRabbitMqConfig;

    public void publish(String routingKey, OpsEvent event) {
        try {
            rabbitTemplate.convertAndSend(opsEventRabbitMqConfig.exchange(), routingKey,
                    JSON.toJSONString(event), new CorrelationData(event.getEventId()));
        } catch (Exception ex) {
            log.warn("publish ops event failed, routingKey={}, eventType={}, message={}",
                    routingKey, event == null ? "" : event.getEventType(), ex.getMessage());
        }
    }
}
