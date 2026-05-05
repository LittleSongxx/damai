package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson.JSON;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.gateway.TraceGateway;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsSkill implements AssistantSkill {

    private static final Pattern TRACE_PATTERN = Pattern.compile("trace(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-z-]+-service)");

    private final ChatClient unifiedOpsChatClient;
    private final AssistantToolInvoker toolInvoker;
    private final LogGateway logGateway;
    private final MetricsGateway metricsGateway;
    private final TraceGateway traceGateway;
    private final AssistantMemoryKeyService memoryKeyService;

    public OpsSkill(@Qualifier("unifiedOpsChatClient") ChatClient unifiedOpsChatClient,
                    AssistantToolInvoker toolInvoker,
                    LogGateway logGateway,
                    MetricsGateway metricsGateway,
                    TraceGateway traceGateway,
                    AssistantMemoryKeyService memoryKeyService) {
        this.unifiedOpsChatClient = unifiedOpsChatClient;
        this.toolInvoker = toolInvoker;
        this.logGateway = logGateway;
        this.metricsGateway = metricsGateway;
        this.traceGateway = traceGateway;
        this.memoryKeyService = memoryKeyService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        String runId = context.getRun().getRunId();
        String prompt = context.getMessage();
        String serviceName = extract(prompt, SERVICE_PATTERN);
        Map<String, Object> evidence;
        String toolName;
        if (prompt.toLowerCase().contains("trace")) {
            String traceId = extract(prompt, TRACE_PATTERN);
            toolName = "traceGateway";
            evidence = invokeGateway(runId, toolName, mapOf("traceId", traceId), () -> traceGateway.getTrace(traceId));
        } else if (containsAny(prompt.toLowerCase(), "jvm", "cpu", "gc", "线程", "监控", "健康")) {
            String resolvedService = serviceName == null ? "damai-ai" : serviceName;
            toolName = "metricsGateway";
            evidence = invokeGateway(runId, toolName, mapOf("serviceName", resolvedService), () -> metricsGateway.getServiceHealthOverview(resolvedService));
        } else if (containsAny(prompt.toLowerCase(), "有哪些服务", "服务列表")) {
            toolName = "metricsGateway";
            evidence = invokeGateway(runId, toolName, mapOf("query", "service-list"), metricsGateway::getServiceList);
        } else {
            toolName = "logGateway";
            evidence = invokeGateway(runId, toolName, mapOf(
                    "keyword", prompt,
                    "serviceName", serviceName == null ? "" : serviceName
            ), () -> logGateway.searchLogsByKeyword(prompt, serviceName, "ERROR", 20));
        }

        String answerPrompt = """
                你是大麦运维助手。请基于给定的真实运维证据做分析，不要编造未出现的数据。

                历史摘要：
                %s

                用户问题：
                %s

                证据：
                %s

                输出要求：
                1. 先概括当前发现。
                2. 再指出最可能的问题位置。
                3. 最后给出下一步排查建议。
                """.formatted(memorySummary(context), prompt, JSON.toJSONString(evidence));
        String answer = unifiedOpsChatClient.prompt()
                .user(answerPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId())))
                .call()
                .content();
        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .build();
    }

    private Map<String, Object> invokeGateway(String runId, String toolName, Object input, SupplierWithException<Map<String, Object>> supplier) {
        return toolInvoker.invoke(runId, toolName, "ops", input, supplier::get);
    }

    private String memorySummary(AssistantSkillContext context) {
        if (context.getMemoryContext() == null || !context.getMemoryContext().present()) {
            return "无";
        }
        return context.getMemoryContext().summary();
    }

    private String extract(String prompt, Pattern pattern) {
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> mapOf(Object... values) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                map.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return map;
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get();
    }
}
