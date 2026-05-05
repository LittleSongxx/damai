package com.damai.service.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CreateOrderRabbitMqConfig {

    @Value("${prefix.distinction.name:damai}")
    private String prefix;
    
    @Value("${damai.rabbitmq.exchange:damai.pro.topic}")
    private String exchange;
    
    @Value("${damai.rabbitmq.create-order.queue:create_order}")
    private String queue;
    
    @Value("${damai.rabbitmq.create-order.routing-key:create_order}")
    private String routingKey;
    
    @Value("${damai.rabbitmq.create-order.dlq:create_order.dlq}")
    private String dlq;
    
    @Bean
    public TopicExchange createOrderExchange() {
        return new TopicExchange(exchange,true,false);
    }
    
    @Bean
    public Queue createOrderQueue() {
        return QueueBuilder.durable(withPrefix(queue))
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", withPrefix(dlq))
                .build();
    }
    
    @Bean
    public Queue createOrderDeadLetterQueue() {
        return QueueBuilder.durable(withPrefix(dlq)).build();
    }
    
    @Bean
    public Binding createOrderBinding(@Qualifier("createOrderExchange") TopicExchange createOrderExchange,
                                      @Qualifier("createOrderQueue") Queue createOrderQueue) {
        return BindingBuilder.bind(createOrderQueue).to(createOrderExchange).with(routingKey());
    }
    
    @Bean
    public Binding createOrderDeadLetterBinding(@Qualifier("createOrderExchange") TopicExchange createOrderExchange,
                                                @Qualifier("createOrderDeadLetterQueue") Queue createOrderDeadLetterQueue) {
        return BindingBuilder.bind(createOrderDeadLetterQueue).to(createOrderExchange).with(withPrefix(dlq));
    }
    
    public String exchange() {
        return exchange;
    }
    
    public String routingKey() {
        return withPrefix(routingKey);
    }
    
    private String withPrefix(String value) {
        if (value.startsWith(prefix + "-")) {
            return value;
        }
        return prefix + "-" + value;
    }
}
