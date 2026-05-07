package org.javaup.ai.assistant;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.assistant.memory.AssistantMemoryContext;
import org.javaup.ai.assistant.profile.AssistantUserProfileContext;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;

import java.util.Map;

@Data
@Builder
public class AssistantSkillContext {

    private AiRun run;

    private AiUserContext user;

    private String message;

    private Map<String, Object> clientContext;

    private AssistantMemoryContext memoryContext;

    private AssistantUserProfileContext userProfileContext;

    private AssistantSkillDescriptor skillDescriptor;

    private AssistantSkillResourceBundle skillResources;

    public static AssistantSkillContext of(AiRun run, AiUserContext user, AssistantRunCreateRequest request) {
        return of(run, user, request, AssistantMemoryContext.empty());
    }

    public static AssistantSkillContext of(AiRun run, AiUserContext user, AssistantRunCreateRequest request, AssistantMemoryContext memoryContext) {
        return of(run, user, request, memoryContext, AssistantUserProfileContext.empty());
    }

    public static AssistantSkillContext of(AiRun run, AiUserContext user, AssistantRunCreateRequest request, AssistantMemoryContext memoryContext, AssistantUserProfileContext userProfileContext) {
        return of(run, user, request, memoryContext, userProfileContext, null, AssistantSkillResourceBundle.empty());
    }

    public static AssistantSkillContext of(AiRun run, AiUserContext user, AssistantRunCreateRequest request, AssistantMemoryContext memoryContext, AssistantUserProfileContext userProfileContext, AssistantSkillDescriptor skillDescriptor, AssistantSkillResourceBundle skillResources) {
        return AssistantSkillContext.builder()
                .run(run)
                .user(user)
                .message(request.getMessage())
                .clientContext(request.getClientContext())
                .memoryContext(memoryContext)
                .userProfileContext(userProfileContext)
                .skillDescriptor(skillDescriptor)
                .skillResources(skillResources == null ? AssistantSkillResourceBundle.empty() : skillResources)
                .build();
    }
}
