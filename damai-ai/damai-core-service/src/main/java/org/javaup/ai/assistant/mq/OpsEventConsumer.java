package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiOpsEventInbox;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventInboxMapper;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpsEventConsumer {

    private static final String CONSUMED = "CONSUMED";
    private static final String FAILED = "FAILED";
    private static final String RECEIVED = "RECEIVED";

    private final AiOpsEventRawMapper eventRawMapper;
    private final AiOpsEventInboxMapper inboxMapper;

    @RabbitListener(queues = "${damai.ai.ops.mq.queue:damai.ai.ops.event.q}")
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!StringUtils.hasText(body)) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        String eventId = "";
        try {
            Map<String, Object> payload = JSON.parseObject(body);
            eventId = text(payload.get("eventId"));
            if (!StringUtils.hasText(eventId)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (!claimInbox(eventId)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            AiOpsEventRaw event = toRawEvent(payload);
            eventRawMapper.insert(event);
            markInbox(eventId, CONSUMED, null);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicate) {
            markInbox(eventId, CONSUMED, null);
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.warn("consume ops event failed, eventId={}, body={}, message={}", eventId, body, ex.getMessage());
            markInbox(eventId, FAILED, ex.getMessage());
            channel.basicReject(deliveryTag, false);
        }
    }

    private boolean claimInbox(String eventId) {
        AiOpsEventInbox existing = inboxMapper.selectOne(Wrappers.lambdaQuery(AiOpsEventInbox.class)
                .eq(AiOpsEventInbox::getEventId, eventId)
                .eq(AiOpsEventInbox::getStatus, 1)
                .last("limit 1"));
        if (existing != null) {
            existing.setDuplicateCount(existing.getDuplicateCount() == null ? 1 : existing.getDuplicateCount() + 1);
            existing.setLastSeenAt(new Date());
            existing.setEditTime(new Date());
            if (!CONSUMED.equals(existing.getConsumeStatus())) {
                existing.setConsumeStatus(RECEIVED);
                existing.setLastError(null);
                inboxMapper.updateById(existing);
                return true;
            }
            inboxMapper.updateById(existing);
            return false;
        }
        AiOpsEventInbox row = new AiOpsEventInbox();
        row.setEventId(eventId);
        row.setConsumeStatus(RECEIVED);
        row.setDuplicateCount(0);
        row.setFirstSeenAt(new Date());
        row.setLastSeenAt(new Date());
        row.setCreateTime(new Date());
        row.setEditTime(new Date());
        row.setStatus(1);
        try {
            inboxMapper.insert(row);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private void markInbox(String eventId, String status, String lastError) {
        if (!StringUtils.hasText(eventId)) {
            return;
        }
        AiOpsEventInbox row = inboxMapper.selectOne(Wrappers.lambdaQuery(AiOpsEventInbox.class)
                .eq(AiOpsEventInbox::getEventId, eventId)
                .eq(AiOpsEventInbox::getStatus, 1)
                .last("limit 1"));
        if (row == null) {
            return;
        }
        row.setConsumeStatus(status);
        row.setLastError(lastError);
        row.setLastSeenAt(new Date());
        row.setEditTime(new Date());
        inboxMapper.updateById(row);
    }

    private AiOpsEventRaw toRawEvent(Map<String, Object> payload) {
        AiOpsEventRaw event = new AiOpsEventRaw();
        event.setEventId(text(payload.get("eventId")));
        event.setEventType(text(payload.get("eventType")));
        event.setSourceService(text(payload.get("sourceService")));
        event.setTraceId(text(payload.get("traceId")));
        event.setSpanId(text(payload.get("spanId")));
        event.setUserId(longValue(payload.get("userId")));
        event.setProgramId(longValue(payload.get("programId")));
        event.setOrderNumber(text(payload.get("orderNumber")));
        event.setReservationId(text(payload.get("reservationId")));
        event.setAmount(decimalValue(payload.get("amount")));
        event.setCount(intValue(payload.get("count")));
        event.setEventStatus(text(payload.get("status")));
        event.setOccurredAt(dateValue(payload.get("occurredAt")));
        event.setPayloadJson(JSON.toJSONString(payload.getOrDefault("payloadJson", Map.of())));
        event.setCreateTime(new Date());
        event.setEditTime(new Date());
        event.setStatus(1);
        return event;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return StringUtils.hasText(text(value)) ? Long.parseLong(text(value)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return StringUtils.hasText(text(value)) ? Integer.parseInt(text(value)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return StringUtils.hasText(text(value)) ? new BigDecimal(text(value)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Date dateValue(Object value) {
        String text = text(value);
        if (!StringUtils.hasText(text)) {
            return new Date();
        }
        try {
            return Date.from(java.time.Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            return Date.from(LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant());
        }
    }
}
