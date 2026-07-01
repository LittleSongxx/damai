package org.javaup.ai.assistant.skill.ops;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AiOpsFaultScenario {

    private String scenarioId;

    private String name;

    private String description;

    private String serviceName;

    private Integer windowMinutes;

    private String severity;

    private String rcaPrompt;

    private Map<String, Object> injectedSignals;

    private Map<String, Object> expectedEvidence;

    private List<String> expectedCauses;

    private List<String> suggestedActions;
}
