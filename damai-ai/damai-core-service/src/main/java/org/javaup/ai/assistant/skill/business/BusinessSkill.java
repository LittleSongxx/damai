package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.entity.AiAction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.legacy")
                .name("业务助手默认 Skill")
                .description("兼容旧版业务路由，承接节目推荐、票档查询和购票准备等业务问题。")
                .version("1.0.0")
                .goal("兼容旧版业务助手能力，处理尚未精确拆分的购票业务请求。")
                .instructions("优先使用工具获取真实节目、票档和购票预览；不能编造业务数据。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("演出", "节目", "票", "购票", "推荐", "城市", "价格"))
                .toolAllowlist(List.of("recommendPrograms", "searchPrograms", "getProgramDetail", "preparePurchase"))
                .examples(List.of("帮我推荐北京演唱会", "查询周杰伦演唱会票档", "帮我生成购票预览"))
                .evalCases(List.of("业务兼容 Skill 不应绕过审批直接创建订单"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.MEDIUM)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(true)
                .build();
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
