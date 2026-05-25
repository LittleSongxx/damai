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
import org.javaup.ai.service.DialogueStateManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class BusinessSkill implements AssistantSkill {

    private final ChatClient unifiedBusinessChatClient;
    private final AssistantRunService assistantRunService;
    private final AssistantMemoryKeyService memoryKeyService;
    private final DialogueStateManager dialogueStateManager;

    public BusinessSkill(@Lazy @Qualifier("unifiedBusinessChatClient") ChatClient unifiedBusinessChatClient,
                         AssistantRunService assistantRunService,
                         AssistantMemoryKeyService memoryKeyService,
                         DialogueStateManager dialogueStateManager) {
        this.unifiedBusinessChatClient = unifiedBusinessChatClient;
        this.assistantRunService = assistantRunService;
        this.memoryKeyService = memoryKeyService;
        this.dialogueStateManager = dialogueStateManager;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.BUSINESS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.unified")
                .name("购票业务助手")
                .description("通过 LLM 自主选择工具完成节目搜索/推荐、详情查询、票档查询和购票预览。")
                .version("2.0.0")
                .goal("基于 ChatClient Tool Calling 处理购票业务全链路请求。")
                .instructions("优先使用工具获取真实节目、票档和购票预览；不能编造业务数据。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("演出", "节目", "票", "购票", "推荐", "城市", "价格",
                        "找", "搜索", "演唱会", "脱口秀", "周末",
                        "详情", "时间", "场馆", "地址", "艺人", "什么时候", "在哪",
                        "票档", "库存", "余票", "多少钱", "座位", "票价"))
                .toolAllowlist(List.of("recommendPrograms", "searchPrograms", "getProgramDetail", "preparePurchase"))
                .examples(List.of("帮我推荐北京演唱会", "周杰伦演唱会什么时候", "查询票档和价格", "帮我生成购票预览"))
                .evalCases(List.of("业务 Skill 不应绕过审批直接创建订单"))
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
        // Dialogue state machine: maintain and update multi-turn state
        DialogueStateManager.DialogueContext dialogueCtx = dialogueStateManager.getOrCreate(
                context.getRun().getConversationId(),
                context.getRun().getUserId(),
                context.getMessage());

        if (!dialogueCtx.isPhase("CONFIRMATION") && !dialogueCtx.isResolved()) {
            String intent = detectIntent(context.getMessage());
            DialogueStateManager.StateTransitionResult stateResult = dialogueStateManager.updateState(
                    dialogueCtx, context.getMessage(), intent);

            if (!stateResult.allSlotsFilled()) {
                return AssistantSkillResult.builder()
                        .message(stateResult.responseMessage())
                        .responseSummary(stateResult.responseMessage())
                        .build();
            }
            // Slots filled: return confirmation message and let user confirm
            return AssistantSkillResult.builder()
                    .message(stateResult.responseMessage())
                    .responseSummary(stateResult.responseMessage())
                    .build();
        }

        // Confirmation phase or resolved: inject collected slots into prompt
        String userPrompt = context.buildUserPrompt();
        if (!dialogueCtx.slots().isEmpty()) {
            StringBuilder slotInfo = new StringBuilder("\n\n已确认的信息：\n");
            dialogueCtx.slots().forEach((k, v) -> slotInfo.append("- ").append(k).append("：").append(v).append("\n"));
            userPrompt = userPrompt + slotInfo.toString();
        }

        String content = unifiedBusinessChatClient.prompt()
                .user(userPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId())))
                .call()
                .content();

        // Mark dialogue as resolved after successful execution
        dialogueStateManager.markResolved(dialogueCtx);

        AiAction pendingAction = assistantRunService.getPendingAction(context.getRun().getRunId());

        // 如果有 Tool Calling 产生了购票快照（pendingAction），附加到结果中
        return AssistantSkillResult.builder()
                .message(content)
                .responseSummary(content)
                .pendingAction(pendingAction)
                .build();
    }

    private static final Set<String> BUY_INTENT_KEYWORDS = Set.of(
            "买", "购票", "下单", "抢票", "订票", "购买", "票价", "座位", "票档",
            "多少钱", "价格", "选座", "支付", "付款");

    private static final Set<String> QUERY_INTENT_KEYWORDS = Set.of(
            "搜索", "找", "推荐", "有什么", "有哪些", "最近", "热门", "排行",
            "演唱会", "脱口秀", "话剧", "音乐节", "演出", "节目", "艺人",
            "什么时候", "在哪", "详情", "介绍", "城市");

    private String detectIntent(String message) {
        if (message == null) return "QUERY_PROGRAM";
        String lower = message.toLowerCase();
        for (String kw : BUY_INTENT_KEYWORDS) {
            if (lower.contains(kw)) return "BUY_TICKET";
        }
        for (String kw : QUERY_INTENT_KEYWORDS) {
            if (lower.contains(kw)) return "QUERY_PROGRAM";
        }
        return "QUERY_PROGRAM";
    }

}
