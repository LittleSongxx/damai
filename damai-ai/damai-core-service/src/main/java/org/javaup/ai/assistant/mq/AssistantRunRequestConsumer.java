package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantRuntimeService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRunRequestConsumer {

    private final AssistantRuntimeService runtimeService;

    @RabbitListener(queues = "${damai.ai.memory.overflow.queue:damai.ai.overflow.run_request.q}",
                    concurrency = "2-4")
    public void onMessage(Message message, Channel channel) throws Exception {
        String body = new String(message.getBody());
        AssistantRunRequestMessage request;
        try {
            request = JSON.parseObject(body, AssistantRunRequestMessage.class);
        } catch (Exception e) {
            log.error("Failed to parse overflow message, discarding", e);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            return;
        }
        try {
            log.info("Processing overflow run request: runId={}", request.getRunId());
            runtimeService.processOverflowRun(request);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("Failed to process overflow run: runId={}", request.getRunId(), e);
            channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
        }
    }
}
