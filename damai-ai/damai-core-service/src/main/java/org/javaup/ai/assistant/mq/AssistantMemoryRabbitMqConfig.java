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

    @Bean
    public TopicExchange assistantMemoryExchange() {
        return new TopicExchange(exchange,true,false);
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

    public String exchange() {
        return exchange;
    }

    public String runCompletedRoutingKey() {
        return runCompletedRoutingKey;
    }
}
