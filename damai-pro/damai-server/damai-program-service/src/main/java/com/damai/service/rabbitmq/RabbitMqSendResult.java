package com.damai.service.rabbitmq;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RabbitMqSendResult {

    private String exchange;
    
    private String routingKey;
    
    private String correlationId;
}
