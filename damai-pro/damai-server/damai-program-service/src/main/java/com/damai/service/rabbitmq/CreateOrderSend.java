package com.damai.service.rabbitmq;

import com.damai.mq.callback.FailureCallback;
import com.damai.mq.callback.SuccessCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class CreateOrderSend {
    
    private final RabbitTemplate rabbitTemplate;
    
    private final CreateOrderRabbitMqConfig createOrderRabbitMqConfig;
    
    public void sendMessage(String message, SuccessCallback<RabbitMqSendResult> successCallback,
                            FailureCallback failureCallback) {
        log.info("创建订单rabbitmq发送消息 消息体 : {}", message);
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);
        try {
            rabbitTemplate.convertAndSend(createOrderRabbitMqConfig.exchange(), createOrderRabbitMqConfig.routingKey(), message, correlationData);
            correlationData.getFuture().whenComplete((confirm, ex) -> {
                if (Objects.nonNull(ex)) {
                    failureCallback.onFailure(ex);
                } else if (Objects.nonNull(confirm) && confirm.isAck()) {
                    successCallback.onSuccess(new RabbitMqSendResult(createOrderRabbitMqConfig.exchange(), createOrderRabbitMqConfig.routingKey(), correlationId));
                } else {
                    String reason = Objects.isNull(confirm) ? "RabbitMQ confirm is null" : confirm.getReason();
                    failureCallback.onFailure(new AmqpException(reason));
                }
            });
        } catch (Exception ex) {
            failureCallback.onFailure(ex);
        }
    }
}
