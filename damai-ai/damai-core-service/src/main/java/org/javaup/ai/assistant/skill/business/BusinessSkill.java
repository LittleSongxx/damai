package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.executor.SkillAgentLoopService;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.entity.AiAction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BusinessSkill implements AssistantSkill {

    private final ChatClient unifiedBusinessChatClient;
    private final AssistantRunService assistantRunService;
    private final AssistantMemoryKeyService memoryKeyService;
    private final SkillAgentLoopService agentLoopService;

    public BusinessSkill(@Lazy @Qualifier("unifiedBusinessChatClient") ChatClient unifiedBusinessChatClient,
                         AssistantRunService assistantRunService,
                         AssistantMemoryKeyService memoryKeyService,
                         @Lazy SkillAgentLoopService agentLoopService) {
        this.unifiedBusinessChatClient = unifiedBusinessChatClient;
        this.assistantRunService = assistantRunService;
        this.memoryKeyService = memoryKeyService;
        this.agentLoopService = agentLoopService;
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
        // Agentic 决策循环: 遵循 Anthropic "Building Effective Agents" Agent 模式
        // 当 Tool Calling 未产生满意结果时自动进入替代策略重试
        List<String> alternativeHints = List.of(
                "扩大搜索城市范围，搜索全国所有城市的该类型演出",
                "尝试搜索该艺人/类型的其他场次或巡演",
                "降低筛选条件（如不限制价格区间），展示更多选项",
                "如果多次尝试仍无结果，诚实告知并建议用户关注大麦APP最新上架信息"
        );

        String content = agentLoopService.executeWithRetry(
                unifiedBusinessChatClient,
                context.getRun().getRunId(),
                context.buildUserPrompt(),
                descriptor(),
                alternativeHints);

        AiAction pendingAction = assistantRunService.getPendingAction(context.getRun().getRunId());

        // 如果有 Tool Calling 产生了购票快照（pendingAction），附加到结果中
        return AssistantSkillResult.builder()
                .message(content)
                .responseSummary(content)
                .pendingAction(pendingAction)
                .build();
    }

}
