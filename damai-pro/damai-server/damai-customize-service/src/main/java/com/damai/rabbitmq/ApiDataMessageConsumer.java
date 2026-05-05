package com.damai.rabbitmq;

import com.alibaba.fastjson.JSON;
import com.damai.entity.ApiData;
import com.damai.service.ApiDataService;
import com.rabbitmq.client.Channel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
@Component
public class ApiDataMessageConsumer {
    
    private ApiDataService apiDataService;
    
    @RabbitListener(queues = "${prefix.distinction.name:damai}-${damai.rabbitmq.save-api-data.queue:save_api_data}")
    public void consumerOrderMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            Optional.ofNullable(message.getBody()).map(body -> new String(body, StandardCharsets.UTF_8)).ifPresent(value -> {
                log.info("consumerOrderMessage message:{}",value);
                ApiData apiData = JSON.parseObject(value, ApiData.class);
                apiDataService.saveApiData(apiData);
            });
            channel.basicAck(deliveryTag,false);
        }catch (Exception e) {
            log.error("consumerApiDataMessage error",e);
            channel.basicReject(deliveryTag,false);
        }
    }
}
