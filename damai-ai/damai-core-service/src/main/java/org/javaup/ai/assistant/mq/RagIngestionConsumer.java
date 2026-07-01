package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.service.DocumentIngestionService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagIngestionConsumer {

    private final DocumentIngestionService ingestionService;

    @RabbitListener(queues = "#{ragIngestionRabbitMqConfig.queueName()}")
    public void onMessage(Message message, Channel channel) {
        String body = new String(message.getBody());
        RagIngestionMessage msg;
        try {
            msg = JSON.parseObject(body, RagIngestionMessage.class);
            if (msg == null || msg.getTaskId() == null) {
                log.warn("Invalid RAG ingestion message: {}", body);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to parse RAG ingestion message", e);
            try { channel.basicAck(message.getMessageProperties().getDeliveryTag(), false); } catch (IOException ignored) {}
            return;
        }

        try {
            log.info("Processing RAG ingestion task: type={}, taskId={}", msg.getTaskType(), msg.getTaskId());
            switch (msg.getTaskType()) {
                case "full" -> ingestionService.reindexAll(msg.getTaskId());
                case "incremental" -> ingestionService.incrementalReindex(msg.getTaskId());
                default -> log.warn("Unknown RAG ingestion task type: {}", msg.getTaskType());
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("RAG ingestion task {} failed", msg.getTaskId(), e);
            try {
                // Reject and don't requeue — task status is persisted in DB
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
            } catch (IOException ignored) {}
        }
    }
}
