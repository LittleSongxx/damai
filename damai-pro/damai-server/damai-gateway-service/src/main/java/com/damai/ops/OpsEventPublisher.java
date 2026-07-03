package com.damai.ops;

import com.damai.domain.OpsEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsEventPublisher {

    private final RedisOpsEventOutboxService outboxService;

    public void publish(String routingKey, OpsEvent event) {
        outboxService.enqueue(routingKey, event);
    }
}
