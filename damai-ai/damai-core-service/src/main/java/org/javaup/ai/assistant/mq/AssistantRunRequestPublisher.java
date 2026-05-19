package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRunRequestPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${damai.ai.memory.mq.exchange:damai.ai.memory.topic}")
    private String exchange;

    @Value("${damai.ai.memory.overflow.routing-key:ai.run.request.overflow}")
    private String routingKey;

    public void publish(AssistantRunRequestMessage message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, JSON.toJSONString(message));
        log.info("Published run request to overflow queue: runId={}", message.getRunId());
    }
}
