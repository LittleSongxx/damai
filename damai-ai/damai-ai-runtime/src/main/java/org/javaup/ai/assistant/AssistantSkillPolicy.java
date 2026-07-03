package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class AssistantSkillPolicy {

    private Boolean requiresAdmin;

    private Boolean requiresApproval;

    private AssistantSkillRiskLevel riskLevel;

    private Boolean frontendSelectable;

    private Boolean modelSelectable;

    public static AssistantSkillPolicy defaults() {
        return AssistantSkillPolicy.builder()
                .requiresAdmin(false)
                .requiresApproval(false)
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .frontendSelectable(true)
                .modelSelectable(true)
                .build();
    }
}
