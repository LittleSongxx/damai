package com.damai.rabbitmq;

import com.damai.domain.OpsEvent;
import com.damai.ops.OpsEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class ApiDataMessageSend {
    
    private final RabbitTemplate rabbitTemplate;
    
    private final ApiDataRabbitMqConfig apiDataRabbitMqConfig;

    private final OpsEventPublisher opsEventPublisher;
    
    public void sendMessage(String message) {
        log.info("sendMessage message : {}", message);
        rabbitTemplate.convertAndSend(apiDataRabbitMqConfig.exchange(), apiDataRabbitMqConfig.routingKey(), message, new CorrelationData(UUID.randomUUID().toString()));
        opsEventPublisher.publish("ops.api.called", OpsEvent.of("API_CALLED", "damai-gateway-service")
                .withStatus("RECORDED")
                .putPayload("apiData", message));
    }
}
