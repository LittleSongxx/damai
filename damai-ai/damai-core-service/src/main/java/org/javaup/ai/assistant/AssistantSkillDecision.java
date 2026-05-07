package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssistantSkillDecision {

    private String skillId;

    private String reason;

    private Double confidence;

    private Boolean fromHint;

    private AssistantSkillDescriptor descriptor;
}
