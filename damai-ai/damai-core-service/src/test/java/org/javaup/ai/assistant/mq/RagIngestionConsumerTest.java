package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.Channel;
import org.javaup.ai.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RagIngestionConsumerTest {

    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final RagIngestionConsumer consumer = new RagIngestionConsumer(ingestionService);

    @Test
    void shouldUseSubmittedTaskIdForFullReindex() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = message(RagIngestionMessage.builder()
                .taskId("ingest_1")
                .taskType("full")
                .build());

        consumer.onMessage(message, channel);

        verify(ingestionService).reindexAll("ingest_1");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldUseSubmittedTaskIdForIncrementalReindex() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = message(RagIngestionMessage.builder()
                .taskId("incr_1")
                .taskType("incremental")
                .build());

        consumer.onMessage(message, channel);

        verify(ingestionService).incrementalReindex("incr_1");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldAckInvalidMessagesWithoutRunningIngestion() throws Exception {
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        Message message = new Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);

        consumer.onMessage(message, channel);

        verify(ingestionService, never()).reindexAll("ingest_1");
        verify(channel).basicAck(1L, false);
    }

    private Message message(RagIngestionMessage payload) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        return new Message(JSON.toJSONString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
