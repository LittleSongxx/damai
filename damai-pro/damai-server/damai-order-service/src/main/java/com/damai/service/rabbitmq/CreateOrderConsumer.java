package com.damai.service.rabbitmq;

import com.alibaba.fastjson.JSON;
import com.damai.core.RedisKeyManage;
import com.damai.domain.DiscardOrder;
import com.damai.domain.OrderCreateMq;
import com.damai.domain.OpsEvent;
import com.damai.dto.OrderTicketUserCreateDto;
import com.damai.enums.DiscardOrderReason;
import com.damai.ops.OpsEventPublisher;
import com.damai.redis.RedisCache;
import com.damai.redis.RedisKeyBuild;
import com.damai.service.OrderService;
import com.damai.util.StringUtil;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderConsumer {
    
    private OrderService orderService;
    
    private RedisCache redisCache;
    
    private MeterRegistry meterRegistry;

    private OpsEventPublisher opsEventPublisher;
    
    public static Long MESSAGE_DELAY_TIME = 5000L;
    
    @RabbitListener(queues = "${prefix.distinction.name:damai}-${damai.rabbitmq.create-order.queue:create_order}")
    public void consumerOrderMessage(Message message, Channel channel) throws IOException {
        String value = new String(message.getBody(), StandardCharsets.UTF_8);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        if (StringUtil.isEmpty(value)) {
            channel.basicAck(deliveryTag,false);
            return;
        }
        OrderCreateMq orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);
        try {
            long createOrderTimeTimestamp = orderCreateMq.getCreateOrderTime().getTime();
            
            long currentTimeTimestamp = System.currentTimeMillis();
            
            long delayTime = currentTimeTimestamp - createOrderTimeTimestamp;
            
            log.info("消费到rabbitmq的创建订单消息 消息体: {} 延迟时间 : {} 毫秒",value,delayTime);
            
            if (currentTimeTimestamp - createOrderTimeTimestamp > MESSAGE_DELAY_TIME) {
                Map<Long, List<OrderTicketUserCreateDto>> orderTicketUserSeatList =
                        orderCreateMq.getOrderTicketUserCreateDtoList().stream().collect(Collectors.groupingBy(OrderTicketUserCreateDto::getTicketCategoryId));
                Map<Long,List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
                orderTicketUserSeatList.forEach((k,v) -> {
                    seatMap.put(k,v.stream().map(OrderTicketUserCreateDto::getSeatId).collect(Collectors.toList()));
                });
                log.info("消费到rabbitmq的创建订单消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {} 座位信息 : {}",
                        delayTime,orderCreateMq.getOrderNumber(),JSON.toJSONString(seatMap));
                redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                        orderCreateMq.getProgramId()),new DiscardOrder(orderCreateMq, DiscardOrderReason.CONSUMER_DELAY.getCode(), "消费延迟"));
                meterRegistry.counter("damai_order_create_fail_total", "reason", "CREATE_ORDER_DELAY", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
                opsEventPublisher.publish("ops.mq.exception", OpsEvent.of("MQ_MESSAGE_EXCEPTION", "damai-order-service")
                        .withUserId(orderCreateMq.getUserId())
                        .withProgramId(orderCreateMq.getProgramId())
                        .withOrderNumber(orderCreateMq.getOrderNumber())
                        .withStatus("DELAYED")
                        .putPayload("topic", "create_order")
                        .putPayload("delayTimeMs", delayTime));
            }else {
                String orderNumber = orderService.createMq(orderCreateMq);
                log.info("消费到rabbitmq的创建订单消息 创建订单成功 订单号 : {}",orderNumber);
                opsEventPublisher.publish("ops.order.created", OpsEvent.of("ORDER_CREATED", "damai-order-service")
                        .withUserId(orderCreateMq.getUserId())
                        .withProgramId(orderCreateMq.getProgramId())
                        .withOrderNumber(orderNumber)
                        .withAmount(orderCreateMq.getOrderPrice())
                        .withStatus("SUCCESS")
                        .putPayload("source", "create_order_mq"));
            }
            channel.basicAck(deliveryTag,false);
        }catch (Exception e) {
            redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                    orderCreateMq.getProgramId()),new DiscardOrder(orderCreateMq, DiscardOrderReason.CREATE_ORDER_FAIL.getCode(), e.getMessage()));
            meterRegistry.counter("damai_order_create_fail_total", "reason", "CREATE_ORDER_FAIL", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
            opsEventPublisher.publish("ops.order.failed", OpsEvent.of("ORDER_CREATE_FAILED", "damai-order-service")
                    .withUserId(orderCreateMq.getUserId())
                    .withProgramId(orderCreateMq.getProgramId())
                    .withOrderNumber(orderCreateMq.getOrderNumber())
                    .withStatus("FAILED")
                    .putPayload("errorMessage", e.getMessage()));
            log.error("处理消费到rabbitmq的创建订单消息失败 error",e);
            channel.basicReject(deliveryTag,false);
        }
    }
}
