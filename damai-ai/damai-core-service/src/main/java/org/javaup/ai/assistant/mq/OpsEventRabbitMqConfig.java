package org.javaup.ai.assistant.mq;

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
public class OpsEventRabbitMqConfig {

    @Value("${damai.ai.ops.mq.exchange:damai.pro.ops.topic}")
    private String exchange;

    @Value("${damai.ai.ops.mq.queue:damai.ai.ops.event.q}")
    private String queueName;

    @Value("${damai.ai.ops.mq.routing-key:ops.#}")
    private String routingKey;

    @Value("${damai.ai.ops.mq.dlq:damai.ai.ops.event.dlq}")
    private String dlq;

    @Bean
    public TopicExchange opsEventExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue opsEventQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    @Bean
    public Queue opsEventDeadLetterQueue() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding opsEventBinding(@Qualifier("opsEventExchange") TopicExchange exchange,
                                   @Qualifier("opsEventQueue") Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    @Bean
    public Binding opsEventDeadLetterBinding(@Qualifier("opsEventExchange") TopicExchange exchange,
                                             @Qualifier("opsEventDeadLetterQueue") Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with(dlq);
    }
}
