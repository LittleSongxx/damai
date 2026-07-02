package com.damai.service.ops;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpsEventRabbitMqConfig {

    @Value("${damai.rabbitmq.ops.exchange:damai.pro.ops.topic}")
    private String exchange;

    @Bean
    public TopicExchange opsEventExchange() {
        return new TopicExchange(exchange, true, false);
    }

    public String exchange() {
        return exchange;
    }
}
