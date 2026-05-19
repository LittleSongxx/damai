package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagIngestionPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RagIngestionRabbitMqConfig mqConfig;

    public void publish(RagIngestionMessage message) {
        String correlationId = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(
                mqConfig.exchange(),
                mqConfig.routingKey(),
                JSON.toJSONString(message),
                new CorrelationData(correlationId));
        log.info("Published RAG ingestion task {} correlationId={}", message.getTaskId(), correlationId);
    }
}
