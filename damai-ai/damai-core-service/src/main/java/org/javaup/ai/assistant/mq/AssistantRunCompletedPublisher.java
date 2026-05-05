package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiRun;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRunCompletedPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final AssistantMemoryRabbitMqConfig rabbitMqConfig;

    public void publish(AiRun run) {
        if (run == null) {
            return;
        }
        AssistantRunCompletedMessage message = new AssistantRunCompletedMessage(
                run.getRunId(),
                run.getConversationId(),
                run.getUserId(),
                run.getRouteType(),
                new Date()
        );
        String correlationId = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(rabbitMqConfig.exchange(), rabbitMqConfig.runCompletedRoutingKey(), JSON.toJSONString(message), new CorrelationData(correlationId));
        log.info("已投递AI记忆画像异步刷新消息 runId : {} correlationId : {}", run.getRunId(), correlationId);
    }
}
