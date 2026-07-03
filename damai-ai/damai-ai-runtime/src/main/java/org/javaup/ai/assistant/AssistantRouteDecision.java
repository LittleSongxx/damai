package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AssistantRouteDecision {

    private AssistantRouteType routeType;

    private String reason;

    private Boolean fromFallback;

    private Boolean clarificationRequired;

    private String clarificationPrompt;

    private List<String> clarificationOptions;
}
