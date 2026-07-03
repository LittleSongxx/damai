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
public class AgentRun {

    private String runId;

    private String conversationId;

    private AgentScene scene;

    private AgentRunStatus status;

    private String route;

    private String stage;

    private String checkpointId;

    private Map<String, Object> checkpoint;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
