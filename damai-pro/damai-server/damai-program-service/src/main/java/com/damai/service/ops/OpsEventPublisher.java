package com.damai.service.ops;

import com.damai.domain.OpsEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpsEventPublisher {

    private final OpsEventOutboxService outboxService;

    public void publish(String routingKey, OpsEvent event) {
        outboxService.enqueue(routingKey, event);
    }
}
