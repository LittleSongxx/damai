package org.javaup.ai.assistant.skill.ops;

import com.alibaba.fastjson.JSON;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.TraceGateway;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsLogQuerySkill implements AssistantSkill {

    private static final Pattern TRACE_PATTERN = Pattern.compile("trace(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-z-]+-service)");

    private final ChatClient unifiedOpsChatClient;
    private final AssistantToolInvoker toolInvoker;
    private final LogGateway logGateway;
    private final TraceGateway traceGateway;
    private final AssistantMemoryKeyService memoryKeyService;

    public OpsLogQuerySkill(@Qualifier("unifiedOpsChatClient") ChatClient unifiedOpsChatClient,
                            AssistantToolInvoker toolInvoker,
                            LogGateway logGateway,
                            TraceGateway traceGateway,
                            AssistantMemoryKeyService memoryKeyService) {
        this.unifiedOpsChatClient = unifiedOpsChatClient;
        this.toolInvoker = toolInvoker;
        this.logGateway = logGateway;
        this.traceGateway = traceGateway;
        this.memoryKeyService = memoryKeyService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.log.query")
                .name("运维日志查询")
                .description("查询错误日志、异常关键词和服务日志线索。")
                .version("1.0.0")
                .goal("查询服务日志、错误日志和 trace 相关线索。")
                .instructions("只能基于日志证据分析，不得编造未出现的服务、异常或 trace。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("日志", "异常", "错误", "error", "exception", "失败", "trace"))
                .toolAllowlist(List.of("logGateway", "traceGateway"))
                .examples(List.of("查询 order-service 最近的 ERROR 日志"))
                .evalCases(List.of("日志查询不能调用 NL2SQL"))
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
        String serviceName = extract(prompt, SERVICE_PATTERN);
        Map<String, Object> evidence;

        if (prompt.toLowerCase().contains("trace")) {
            String traceId = extract(prompt, TRACE_PATTERN);
            evidence = toolInvoker.invoke(runId, "traceGateway", "ops",
                    mapOf("traceId", traceId),
                    () -> traceGateway.getTrace(traceId));
        } else {
            evidence = toolInvoker.invoke(runId, "logGateway", "ops",
                    mapOf("keyword", prompt, "serviceName", serviceName == null ? "" : serviceName),
                    () -> logGateway.searchLogsByKeyword(prompt, serviceName, "ERROR", 20));
        }

        return generateAnswer(context, prompt, evidence);
    }

    private AssistantSkillResult generateAnswer(AssistantSkillContext context, String prompt, Map<String, Object> evidence) {
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
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Map<String, Object> mapOf(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                map.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return map;
    }
}
