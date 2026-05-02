package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRetrieval;

@Data
@Builder
public class AssistantSkillResult {

    private String message;

    private String responseSummary;

    private AiAction pendingAction;

    private AiRetrieval retrieval;
}
