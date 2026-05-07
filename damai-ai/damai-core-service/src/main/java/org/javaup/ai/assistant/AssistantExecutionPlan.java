package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssistantExecutionPlan {

    private String runId;

    private String conversationId;

    private String originalMessage;

    private Map<String, Object> clientContext;

    private AssistantExecutionMode executionMode;

    private AssistantRouteDecision routeDecision;

    private AssistantSkillDecision skillDecision;

    private String responseMessage;

    private List<String> options;

    private String reason;
}
