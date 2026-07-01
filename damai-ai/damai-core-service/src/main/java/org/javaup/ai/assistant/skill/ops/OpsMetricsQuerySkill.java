package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.utils.CommonUtils;
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
    private static final Pattern TRACE_PATTERN = Pattern.compile("trace(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_PATTERN = Pattern.compile("span(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);

    private final ChatClient unifiedOpsChatClient;
    private final AssistantToolInvoker toolInvoker;
    private final MetricsGateway metricsGateway;
    private final AssistantMemoryKeyService memoryKeyService;
    private final OpsRcaEvidenceService rcaEvidenceService;

    public OpsMetricsQuerySkill(@Qualifier("unifiedOpsChatClient") ChatClient unifiedOpsChatClient,
                                AssistantToolInvoker toolInvoker,
                                MetricsGateway metricsGateway,
                                AssistantMemoryKeyService memoryKeyService,
                                OpsRcaEvidenceService rcaEvidenceService) {
        this.unifiedOpsChatClient = unifiedOpsChatClient;
        this.toolInvoker = toolInvoker;
        this.metricsGateway = metricsGateway;
        this.memoryKeyService = memoryKeyService;
        this.rcaEvidenceService = rcaEvidenceService;
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

        if (CommonUtils.containsAny(prompt.toLowerCase(), "有哪些服务", "服务列表")) {
            evidence = toolInvoker.invoke(runId, "metricsGateway", "ops",
                    Map.of("query", "service-list"),
                    metricsGateway::getServiceList);
        } else {
            Matcher matcher = SERVICE_PATTERN.matcher(prompt);
            String serviceName = matcher.find() ? matcher.group(1) : "damai-ai";
            Map<String, Object> primaryEvidence = toolInvoker.invoke(runId, "metricsGateway", "ops",
                    Map.of("serviceName", serviceName),
                    () -> metricsGateway.getServiceHealthOverview(serviceName));
            OpsRcaRequest rcaRequest = new OpsRcaRequest();
            rcaRequest.setQuery(prompt);
            rcaRequest.setServiceName(serviceName);
            rcaRequest.setTraceId(extract(prompt, TRACE_PATTERN));
            rcaRequest.setSpanId(extract(prompt, SPAN_PATTERN));
            Map<String, Object> evidenceBundle = toolInvoker.invoke(runId, "opsRcaEvidence", "ops",
                    CommonUtils.mapOf("query", prompt,
                            "serviceName", serviceName,
                            "traceId", rcaRequest.getTraceId(),
                            "spanId", rcaRequest.getSpanId()),
                    () -> rcaEvidenceService.buildEvidenceBundle(rcaRequest));
            evidence = Map.of("primaryEvidence", primaryEvidence, "evidenceBundle", evidenceBundle);
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
                2. 对异常指标给出可能原因分析，并说明证据链。
                3. 最后给出优化或排查建议，必须引用 evidenceBundle 中的 SLO、日志、指标或 trace 线索。
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

    private String extract(String prompt, Pattern pattern) {
        Matcher matcher = pattern.matcher(prompt == null ? "" : prompt);
        return matcher.find() ? matcher.group(1) : null;
    }

}
