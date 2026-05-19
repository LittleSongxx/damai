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
public class AssistantMemoryRabbitMqConfig {

    @Value("${damai.ai.memory.mq.exchange:damai.ai.memory.topic}")
    private String exchange;

    @Value("${damai.ai.memory.mq.run-completed-queue:damai.ai.memory.run_completed.q}")
    private String runCompletedQueue;

    @Value("${damai.ai.memory.mq.run-completed-routing-key:ai.run.completed}")
    private String runCompletedRoutingKey;

    @Value("${damai.ai.memory.mq.run-completed-dlq:damai.ai.memory.run_completed.dlq}")
    private String runCompletedDlq;

    @Value("${damai.ai.memory.overflow.queue:damai.ai.overflow.run_request.q}")
    private String overflowQueueName;

    @Value("${damai.ai.memory.overflow.routing-key:ai.run.request.overflow}")
    private String overflowRoutingKey;

    @Value("${damai.ai.memory.overflow.dlq:damai.ai.overflow.run_request.dlq}")
    private String overflowDlq;

    @Value("${damai.ai.memory.overflow.message-ttl-ms:300000}")
    private int overflowMessageTtlMs;

    @Value("${damai.ai.memory.overflow.max-length:10000}")
    private int overflowMaxLength;

    // --- Memory (existing) ---

    @Bean
    public TopicExchange assistantMemoryExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue assistantRunCompletedQueue() {
        return QueueBuilder.durable(runCompletedQueue)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", runCompletedDlq)
                .build();
    }

    @Bean
    public Queue assistantRunCompletedDeadLetterQueue() {
        return QueueBuilder.durable(runCompletedDlq).build();
    }

    @Bean
    public Binding assistantRunCompletedBinding(@Qualifier("assistantMemoryExchange") TopicExchange assistantMemoryExchange,
                                                @Qualifier("assistantRunCompletedQueue") Queue assistantRunCompletedQueue) {
        return BindingBuilder.bind(assistantRunCompletedQueue).to(assistantMemoryExchange).with(runCompletedRoutingKey);
    }

    @Bean
    public Binding assistantRunCompletedDeadLetterBinding(@Qualifier("assistantMemoryExchange") TopicExchange assistantMemoryExchange,
                                                          @Qualifier("assistantRunCompletedDeadLetterQueue") Queue assistantRunCompletedDeadLetterQueue) {
        return BindingBuilder.bind(assistantRunCompletedDeadLetterQueue).to(assistantMemoryExchange).with(runCompletedDlq);
    }

    // --- Overflow (new) ---

    @Bean
    public Queue assistantOverflowQueue() {
        return QueueBuilder.durable(overflowQueueName)
                .withArgument("x-message-ttl", overflowMessageTtlMs)
                .withArgument("x-max-length", overflowMaxLength)
                .withArgument("x-dead-letter-exchange", exchange)
                .withArgument("x-dead-letter-routing-key", overflowDlq)
                .build();
    }

    @Bean
    public Queue assistantOverflowDeadLetterQueue() {
        return QueueBuilder.durable(overflowDlq).build();
    }

    @Bean
    public Binding assistantOverflowBinding(@Qualifier("assistantMemoryExchange") TopicExchange exchange,
                                            @Qualifier("assistantOverflowQueue") Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with(overflowRoutingKey);
    }

    @Bean
    public Binding assistantOverflowDlqBinding(@Qualifier("assistantMemoryExchange") TopicExchange exchange,
                                               @Qualifier("assistantOverflowDeadLetterQueue") Queue dlq) {
        return BindingBuilder.bind(dlq).to(exchange).with(overflowDlq);
    }

    public String exchange() {
        return exchange;
    }

    public String runCompletedRoutingKey() {
        return runCompletedRoutingKey;
    }
}
