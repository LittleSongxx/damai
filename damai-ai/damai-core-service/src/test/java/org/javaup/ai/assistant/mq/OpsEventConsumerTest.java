package org.javaup.ai.assistant.mq;

import com.rabbitmq.client.Channel;
import org.javaup.ai.entity.AiOpsEventInbox;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventInboxMapper;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsEventConsumerTest {

    @Test
    void duplicateEventShouldAckWithoutWritingRawAgain() throws Exception {
        AiOpsEventRawMapper rawMapper = mock(AiOpsEventRawMapper.class);
        AiOpsEventInboxMapper inboxMapper = mock(AiOpsEventInboxMapper.class);
        OpsEventConsumer consumer = new OpsEventConsumer(rawMapper, inboxMapper);
        Channel channel = mock(Channel.class);
        Message message = message("""
                {"eventId":"evt-1","eventType":"ORDER_CREATED","sourceService":"damai-program-service","occurredAt":"2026-07-03T06:00:00Z"}
                """, 10L);

        AiOpsEventInbox existing = new AiOpsEventInbox();
        existing.setId(1L);
        existing.setEventId("evt-1");
        existing.setConsumeStatus("CONSUMED");
        existing.setDuplicateCount(2);
        existing.setLastSeenAt(new Date());
        when(inboxMapper.selectOne(any())).thenReturn(existing);

        consumer.consume(message, channel);

        verify(rawMapper, never()).insert(any(AiOpsEventRaw.class));
        verify(inboxMapper).updateById(existing);
        verify(channel).basicAck(10L, false);
    }

    @Test
    void firstEventShouldClaimInboxWriteRawAndAck() throws Exception {
        AiOpsEventRawMapper rawMapper = mock(AiOpsEventRawMapper.class);
        AiOpsEventInboxMapper inboxMapper = mock(AiOpsEventInboxMapper.class);
        OpsEventConsumer consumer = new OpsEventConsumer(rawMapper, inboxMapper);
        Channel channel = mock(Channel.class);
        Message message = message("""
                {"eventId":"evt-2","eventType":"AI_RESERVATION_CONFIRMED","sourceService":"damai-program-service","reservationId":"air_1","status":"CONFIRMED","occurredAt":"2026-07-03T06:00:00Z"}
                """, 11L);

        AiOpsEventInbox consumed = new AiOpsEventInbox();
        consumed.setId(2L);
        consumed.setEventId("evt-2");
        when(inboxMapper.selectOne(any())).thenReturn(null, consumed);

        consumer.consume(message, channel);

        verify(inboxMapper).insert(any(AiOpsEventInbox.class));
        verify(rawMapper).insert(any(AiOpsEventRaw.class));
        verify(inboxMapper).updateById(consumed);
        verify(channel).basicAck(eq(11L), eq(false));
    }

    @Test
    void failedInboxShouldBeRetriedInsteadOfSkipped() throws Exception {
        AiOpsEventRawMapper rawMapper = mock(AiOpsEventRawMapper.class);
        AiOpsEventInboxMapper inboxMapper = mock(AiOpsEventInboxMapper.class);
        OpsEventConsumer consumer = new OpsEventConsumer(rawMapper, inboxMapper);
        Channel channel = mock(Channel.class);
        Message message = message("""
                {"eventId":"evt-3","eventType":"PAYMENT_SUCCESS","sourceService":"damai-order-service","status":"SUCCESS","occurredAt":"2026-07-03T06:00:00Z"}
                """, 12L);

        AiOpsEventInbox failed = new AiOpsEventInbox();
        failed.setId(3L);
        failed.setEventId("evt-3");
        failed.setConsumeStatus("FAILED");
        failed.setDuplicateCount(1);
        failed.setLastError("temporary database error");
        when(inboxMapper.selectOne(any())).thenReturn(failed, failed);

        consumer.consume(message, channel);

        verify(rawMapper).insert(any(AiOpsEventRaw.class));
        verify(inboxMapper, atLeast(2)).updateById(failed);
        org.junit.jupiter.api.Assertions.assertEquals("CONSUMED", failed.getConsumeStatus());
        verify(channel).basicAck(12L, false);
    }

    private Message message(String body, long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
