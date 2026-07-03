package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    private String eventId;

    private String runId;

    private String conversationId;

    private AgentEventType type;

    private String stage;

    private Map<String, Object> payload;

    private LocalDateTime occurredAt;
}
