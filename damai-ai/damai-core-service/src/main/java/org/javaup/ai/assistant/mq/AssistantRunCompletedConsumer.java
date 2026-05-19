package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.memory.AssistantMemoryService;
import org.javaup.ai.assistant.profile.AssistantUserProfileService;
import org.javaup.ai.entity.AiRun;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRunCompletedConsumer {

    private final AssistantRunService runService;

    private final AssistantMemoryService memoryService;

    private final AssistantUserProfileService userProfileService;

    @RabbitListener(queues = "${damai.ai.memory.mq.run-completed-queue:damai.ai.memory.run_completed.q}")
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String content = new String(message.getBody(), StandardCharsets.UTF_8);
        AssistantRunCompletedMessage payload = StringUtils.hasText(content) ? JSON.parseObject(content, AssistantRunCompletedMessage.class) : null;
        if (payload == null || !StringUtils.hasText(payload.getRunId())) {
            channel.basicAck(deliveryTag,false);
            return;
        }
        try {
            AiRun run = runService.getRun(payload.getRunId(), payload.getUserId());
            memoryService.refreshAfterRun(run);
            userProfileService.refreshAfterRun(run);
            channel.basicAck(deliveryTag,false);
            log.info("AI记忆画像异步刷新完成 runId : {}", payload.getRunId());
        } catch (Exception ex) {
            log.error("AI记忆画像异步刷新失败 runId : {}", payload.getRunId(), ex);
            channel.basicReject(deliveryTag,false);
        }
    }
}
