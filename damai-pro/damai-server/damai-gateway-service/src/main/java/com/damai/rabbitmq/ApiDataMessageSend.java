package com.damai.rabbitmq;

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
    
    public void sendMessage(String message) {
        log.info("sendMessage message : {}", message);
        rabbitTemplate.convertAndSend(apiDataRabbitMqConfig.exchange(), apiDataRabbitMqConfig.routingKey(), message, new CorrelationData(UUID.randomUUID().toString()));
    }
}
