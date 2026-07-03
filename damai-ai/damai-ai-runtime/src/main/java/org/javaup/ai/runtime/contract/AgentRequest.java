package org.javaup.ai.runtime.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.context.AiUserContext;

import java.time.Duration;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    private String requestId;

    private String conversationId;

    private AiUserContext user;

    private AgentScene scene;

    private String input;

    private Map<String, Object> context;

    private RuntimeBudget budget;

    private Duration sla;

    private CapabilityRiskLevel riskHint;
}
