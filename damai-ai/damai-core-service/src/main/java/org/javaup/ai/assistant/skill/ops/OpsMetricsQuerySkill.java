package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson.JSON;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsMetricsQuerySkill implements AssistantSkill {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-z-]+-service)");

    private final ChatClient unifiedOpsChatClient;
    private final AssistantToolInvoker toolInvoker;
    private final MetricsGateway metricsGateway;
    private final AssistantMemoryKeyService memoryKeyService;

    public OpsMetricsQuerySkill(@Qualifier("unifiedOpsChatClient") ChatClient unifiedOpsChatClient,
                                AssistantToolInvoker toolInvoker,
                                MetricsGateway metricsGateway,
                                AssistantMemoryKeyService memoryKeyService) {
        this.unifiedOpsChatClient = unifiedOpsChatClient;
        this.toolInvoker = toolInvoker;
        this.metricsGateway = metricsGateway;
        this.memoryKeyService = memoryKeyService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.metrics.query")
                .name("运维指标查询")
                .description("查询服务健康、CPU、JVM、GC、线程和监控指标概览。")
                .version("1.0.0")
                .goal("查询 Prometheus 指标并基于真实指标给出服务健康分析。")
                .instructions("只能调用指标工具，不得把日志或 SQL 结果伪装成监控数据。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("指标", "监控", "cpu", "jvm", "gc", "线程", "健康", "有哪些服务", "服务列表"))
                .toolAllowlist(List.of("metricsGateway"))
                .examples(List.of("看一下 damai-ai 的 JVM 健康情况"))
                .evalCases(List.of("指标查询不能调用下单或 NL2SQL 工具"))
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
        Map<String, Object> evidence;

        if (containsAny(prompt.toLowerCase(), "有哪些服务", "服务列表")) {
            evidence = toolInvoker.invoke(runId, "metricsGateway", "ops",
                    Map.of("query", "service-list"),
                    metricsGateway::getServiceList);
        } else {
            Matcher matcher = SERVICE_PATTERN.matcher(prompt);
            String serviceName = matcher.find() ? matcher.group(1) : "damai-ai";
            evidence = toolInvoker.invoke(runId, "metricsGateway", "ops",
                    Map.of("serviceName", serviceName),
                    () -> metricsGateway.getServiceHealthOverview(serviceName));
        }

        return generateAnswer(context, prompt, evidence);
    }

    private AssistantSkillResult generateAnswer(AssistantSkillContext context, String prompt, Map<String, Object> evidence) {
        String answerPrompt = """
                你是大麦运维助手。请基于给定的真实监控指标做分析，不要编造未出现的数据。

                历史摘要：
                %s

                用户问题：
                %s

                证据：
                %s

                输出要求：
                1. 先概括当前服务健康状况。
                2. 对异常指标给出可能原因分析。
                3. 最后给出优化或排查建议。
                """.formatted(memorySummary(context), prompt, JSON.toJSONString(evidence));
        String answer = unifiedOpsChatClient.prompt()
                .user(answerPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID,
                        memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId())))
                .call()
                .content();
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

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
