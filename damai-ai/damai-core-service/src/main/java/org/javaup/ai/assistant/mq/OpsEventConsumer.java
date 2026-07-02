package org.javaup.ai.assistant.mq;

import com.alibaba.fastjson2.JSON;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

    private final AiOpsEventRawMapper eventRawMapper;

    @RabbitListener(queues = "${damai.ai.ops.mq.queue:damai.ai.ops.event.q}")
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!StringUtils.hasText(body)) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            Map<String, Object> payload = JSON.parseObject(body);
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
            eventRawMapper.insert(event);
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.warn("consume ops event failed, body={}, message={}", body, ex.getMessage());
            channel.basicReject(deliveryTag, false);
        }
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
