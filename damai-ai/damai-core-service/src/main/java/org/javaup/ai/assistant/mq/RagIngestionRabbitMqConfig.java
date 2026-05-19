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
public class RagIngestionRabbitMqConfig {

    @Value("${damai.ai.rag.ingestion.mq.exchange:damai.ai.rag.topic}")
    private String exchange;

    @Value("${damai.ai.rag.ingestion.mq.queue:damai.ai.rag.ingestion.q}")
    private String queueName;

    @Value("${damai.ai.rag.ingestion.mq.routing-key:rag.ingestion.request}")
    private String routingKey;

    @Value("${damai.ai.rag.ingestion.mq.dlq:damai.ai.rag.ingestion.dlq}")
    private String dlq;

    @Bean
    public TopicExchange ragIngestionExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue ragIngestionQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    @Bean
    public Queue ragIngestionDeadLetterQueue() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    public Binding ragIngestionBinding(@Qualifier("ragIngestionExchange") TopicExchange exchange,
                                        @Qualifier("ragIngestionQueue") Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    @Bean
    public Binding ragIngestionDlqBinding(@Qualifier("ragIngestionExchange") TopicExchange exchange,
                                           @Qualifier("ragIngestionDeadLetterQueue") Queue dlqQueue) {
        return BindingBuilder.bind(dlqQueue).to(exchange).with(dlq);
    }

    public String exchange() { return exchange; }

    public String routingKey() { return routingKey; }

    public String queueName() { return queueName; }
}
