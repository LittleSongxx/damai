package org.javaup.ai.assistant.executor;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.assistant.AssistantExecutionPlan;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;

@Data
@Builder
public class AssistantExecutionContext {

    private AiRun run;

    private AssistantRunCreateRequest request;

    private AssistantExecutionPlan plan;

    private AiUserContext user;
}
