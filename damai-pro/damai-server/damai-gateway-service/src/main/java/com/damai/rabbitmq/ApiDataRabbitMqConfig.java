package com.damai.rabbitmq;

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
public class ApiDataRabbitMqConfig {

    @Value("${prefix.distinction.name:damai}")
    private String prefix;
    
    @Value("${damai.rabbitmq.exchange:damai.pro.topic}")
    private String exchange;
    
    @Value("${damai.rabbitmq.save-api-data.queue:save_api_data}")
    private String queue;
    
    @Value("${damai.rabbitmq.save-api-data.routing-key:save_api_data}")
    private String routingKey;
    
    @Value("${damai.rabbitmq.save-api-data.dlq:save_api_data.dlq}")
    private String dlq;
    
    @Bean
    public TopicExchange apiDataExchange() {
        return new TopicExchange(exchange,true,false);
    }
    
    @Bean
    public Queue apiDataQueue() {
        return QueueBuilder.durable(withPrefix(queue))
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", withPrefix(dlq))
                .build();
    }
    
    @Bean
    public Queue apiDataDeadLetterQueue() {
        return QueueBuilder.durable(withPrefix(dlq)).build();
    }
    
    @Bean
    public Binding apiDataBinding(@Qualifier("apiDataExchange") TopicExchange apiDataExchange,
                                  @Qualifier("apiDataQueue") Queue apiDataQueue) {
        return BindingBuilder.bind(apiDataQueue).to(apiDataExchange).with(routingKey());
    }
    
    @Bean
    public Binding apiDataDeadLetterBinding(@Qualifier("apiDataExchange") TopicExchange apiDataExchange,
                                            @Qualifier("apiDataDeadLetterQueue") Queue apiDataDeadLetterQueue) {
        return BindingBuilder.bind(apiDataDeadLetterQueue).to(apiDataExchange).with(withPrefix(dlq));
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
