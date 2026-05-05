package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.entity.AiAction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BusinessSkill implements AssistantSkill {

    private final ChatClient unifiedBusinessChatClient;
    private final AssistantRunService assistantRunService;
    private final AssistantMemoryKeyService memoryKeyService;

    public BusinessSkill(@Qualifier("unifiedBusinessChatClient") ChatClient unifiedBusinessChatClient,
                         AssistantRunService assistantRunService,
                         AssistantMemoryKeyService memoryKeyService) {
        this.unifiedBusinessChatClient = unifiedBusinessChatClient;
        this.assistantRunService = assistantRunService;
        this.memoryKeyService = memoryKeyService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.BUSINESS;
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        String content = unifiedBusinessChatClient.prompt()
                .user(withContext(context))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId())))
                .call()
                .content();
        AiAction pendingAction = assistantRunService.getPendingAction(context.getRun().getRunId());
        return AssistantSkillResult.builder()
                .message(content)
                .responseSummary(content)
                .pendingAction(pendingAction)
                .build();
    }

    private String withContext(AssistantSkillContext context) {
        return """
                用户偏好画像：
                %s

                历史摘要：
                %s

                当前问题：
                %s
                """.formatted(userProfile(context), memorySummary(context), context.getMessage());
    }

    private String memorySummary(AssistantSkillContext context) {
        if (context.getMemoryContext() == null || !context.getMemoryContext().present()) {
            return "无";
        }
        return context.getMemoryContext().summary();
    }

    private String userProfile(AssistantSkillContext context) {
        if (context.getUserProfileContext() == null || !context.getUserProfileContext().present()) {
            return "无";
        }
        return context.getUserProfileContext().summary() + "；偏好标签：" + context.getUserProfileContext().preferenceTagsJson();
    }
}
