package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.executor.SkillAgentLoopService;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlOrchestrator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OpsNl2SqlQuerySkill implements AssistantSkill {

    private final ChatClient unifiedOpsChatClient;
    private final Nl2SqlOrchestrator nl2SqlOrchestrator;
    private final AssistantMemoryKeyService memoryKeyService;
    private final SkillAgentLoopService agentLoopService;
    private final DiagnosticPlanner diagnosticPlanner;

    public OpsNl2SqlQuerySkill(@Qualifier("unifiedOpsChatClient") ChatClient unifiedOpsChatClient,
                               Nl2SqlOrchestrator nl2SqlOrchestrator,
                               AssistantMemoryKeyService memoryKeyService,
                               SkillAgentLoopService agentLoopService,
                               DiagnosticPlanner diagnosticPlanner) {
        this.unifiedOpsChatClient = unifiedOpsChatClient;
        this.nl2SqlOrchestrator = nl2SqlOrchestrator;
        this.memoryKeyService = memoryKeyService;
        this.agentLoopService = agentLoopService;
        this.diagnosticPlanner = diagnosticPlanner;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.nl2sql.query")
                .name("运维问数 NL2SQL")
                .description("面向管理员的受控问数能力，将经营/运维问题转换为安全只读 SQL 并基于结果回答。")
                .version("1.0.0")
                .goal("将管理员的经营或运维问数问题转换为安全只读 SQL 并基于结果回答。")
                .instructions("只能使用白名单视图和 SELECT 查询，禁止敏感字段、DDL/DML、多语句和 select *。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("问数", "统计", "sql", "订单量", "支付成功率", "退款率", "票档库存", "接口调用量", "消息异常"))
                .toolAllowlist(List.of("nl2sql.*"))
                .examples(List.of("今天订单量和支付成功率怎么样"))
                .evalCases(List.of("NL2SQL 只能生成受控 SELECT"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.HIGH)
                .requiresAdmin(true)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        String runId = context.getRun().getRunId();
        String prompt = context.getMessage();
        String conversationKey = memoryKeyService.userConversationKey(
                context.getRun().getUserId(), context.getRun().getConversationId());

        Map<String, Object> evidence = nl2SqlOrchestrator.answer(runId, prompt, conversationKey);

        // LangGraph chain 模式: 规划诊断推理链，引导 LLM 按步骤排查
        DiagnosticPlan diagnosticPlan = diagnosticPlanner.plan(prompt);
        String diagnosticContext = formatDiagnosticPlan(diagnosticPlan);

        String answerPrompt = """
                你是大麦运维助手。请基于给定的真实运维证据做分析，不要编造未出现的数据。

                历史摘要：
                %s

                用户问题：
                %s

                %s

                证据：
                %s

                输出要求：
                1. 先概括当前发现。
                2. 再指出最可能的问题位置。
                3. 如果证据类型是 nl2sql，必须说明 SQL 是否已执行；如果未配置只读数据源，只展示已生成并校验的 SQL 和配置缺口。
                4. 最后按诊断链给出下一步排查建议。
                """.formatted(memorySummary(context), prompt, diagnosticContext, JSON.toJSONString(evidence));

        // Agentic 决策循环: 若第一轮分析未找到根因，自动拓展排查维度
        List<String> alternativeHints = List.of(
                "扩大时间窗口重新查询（如前推/后推 1 小时）",
                "检查同一 traceId 上下游服务的日志和指标",
                "对比同时段正常状态的基线指标数据",
                "如果多次排查仍无法定位，汇总已有发现并建议人工介入"
        );

        String answer = agentLoopService.executeWithRetry(
                unifiedOpsChatClient,
                runId,
                answerPrompt,
                descriptor(),
                alternativeHints);

        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .build();
    }

    private String memorySummary(AssistantSkillContext context) {
        if (context.getMemoryContext() == null || !context.getMemoryContext().present()) {
            return "无";
        }
        return context.getMemoryContext().summary();
    }

    /**
     * 将诊断计划格式化为 LLM 可读的诊断链描述 —— 遵循 LangGraph chain 模式。
     */
    private String formatDiagnosticPlan(DiagnosticPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("诊断推理链（共 " + plan.getTotalSteps() + " 步）：\n");
        List<DiagnosticPlan.Step> steps = plan.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            DiagnosticPlan.Step step = steps.get(i);
            sb.append("  " + (i + 1) + ". [" + step.phase() + "] " + step.toolName());
            sb.append(": " + step.description());
            if (!step.serviceTargets().isEmpty()) {
                sb.append("（目标服务: " + String.join(", ", step.serviceTargets()) + "）");
            }
            if (step.conditional()) {
                sb.append(" [条件: " + step.conditionDescription() + "]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
