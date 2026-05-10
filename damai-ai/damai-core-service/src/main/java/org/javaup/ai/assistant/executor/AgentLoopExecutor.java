package org.javaup.ai.assistant.executor;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.config.AgentLoopProperties;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.tracing.AiSpanService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AgentLoopExecutor implements AssistantExecutor {

    private final AgentLoopProperties properties;
    private final AssistantRunService runService;
    private final AssistantSkillRegistry skillRegistry;
    private final AssistantMessageEmitter messageEmitter;
    private final AiSpanService spanService;
    private final ChatClient chatClient;

    public AgentLoopExecutor(AgentLoopProperties properties,
                             AssistantRunService runService,
                             AssistantSkillRegistry skillRegistry,
                             AssistantMessageEmitter messageEmitter,
                             AiSpanService spanService,
                             @Qualifier("unifiedChatClient") ChatClient chatClient) {
        this.properties = properties;
        this.runService = runService;
        this.skillRegistry = skillRegistry;
        this.messageEmitter = messageEmitter;
        this.spanService = spanService;
        this.chatClient = chatClient;
    }

    @Override
    public AssistantExecutionMode mode() {
        return AssistantExecutionMode.AGENT_LOOP;
    }

    @Override
    public void execute(AssistantExecutionContext context) {
        AiRun run = context.getRun();
        String userMessage = context.getRequest().getMessage();
        List<Map<String, Object>> stepHistory = new ArrayList<>();

        io.opentelemetry.api.trace.Span rootSpan = spanService.startSpan("agent_loop");
        spanService.setRunAttributes(rootSpan, run.getRunId(), null, null);

        try {
            for (int step = 1; step <= properties.getMaxSteps(); step++) {
                io.opentelemetry.api.trace.Span stepSpan = spanService.startSpan("agent_step_" + step, rootSpan);

                String planPrompt = buildPlanPrompt(userMessage, stepHistory);
                String planResult = chatClient.prompt().user(planPrompt).call().content();

                Map<String, Object> stepPayload = new HashMap<>();
                stepPayload.put("step", step);
                stepPayload.put("plan", planResult);

                if (planResult != null && planResult.contains("FINAL_ANSWER:")) {
                    String finalAnswer = planResult.substring(planResult.indexOf("FINAL_ANSWER:") + "FINAL_ANSWER:".length()).trim();
                    stepPayload.put("action", "final_answer");
                    stepPayload.put("result", finalAnswer);
                    runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, stepPayload);
                    messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), finalAnswer);
                    spanService.endSpanSuccess(stepSpan);
                    break;
                }

                String action = extractAction(planResult);
                stepPayload.put("action", action);
                runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, stepPayload);

                String observation = executeAction(action, userMessage, context);
                stepPayload.put("observation", observation);
                stepHistory.add(stepPayload);

                spanService.endSpanSuccess(stepSpan);

                if (step == properties.getMaxSteps()) {
                    String summary = "经过多步思考，以下是我的总结：\n" + observation;
                    messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), summary);
                }
            }

            runService.markCompleted(run, "RESPONDED", "agent_loop_completed");
            spanService.endSpanSuccess(rootSpan);
        } catch (Exception e) {
            log.error("Agent loop error: runId={}, error={}", run.getRunId(), e.getMessage(), e);
            runService.markFailed(run, "FAILED", e.getMessage());
            spanService.endSpanError(rootSpan, e);
        }
    }

    private String buildPlanPrompt(String userMessage, List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个智能助手，正在逐步完成用户的请求。
                请按照 Observe→Plan→Act→Verify 的循环来推进。
                
                如果你可以直接给出最终答案，请以 "FINAL_ANSWER:" 开头输出最终答案。
                如果你需要执行某个动作，请以 "ACTION:" 开头输出你要执行的动作描述。
                可选动作：search_knowledge, query_data, general_chat
                
                """);
        sb.append("【用户问题】\n").append(userMessage).append("\n\n");

        if (!history.isEmpty()) {
            sb.append("【历史步骤】\n");
            for (Map<String, Object> step : history) {
                sb.append("Step ").append(step.get("step")).append(": ")
                        .append(step.get("action")).append(" → ").append(step.get("observation")).append("\n");
            }
        }
        return sb.toString();
    }

    private String extractAction(String planResult) {
        if (planResult == null) return "general_chat";
        if (planResult.contains("ACTION:")) {
            return planResult.substring(planResult.indexOf("ACTION:") + "ACTION:".length()).trim().split("\\s+")[0];
        }
        return "general_chat";
    }

    private String executeAction(String action, String userMessage, AssistantExecutionContext context) {
        try {
            if ("search_knowledge".equals(action)) {
                AssistantSkill knowledgeSkill = skillRegistry.getRequired("knowledge");
                AssistantSkillContext skillContext = AssistantSkillContext.builder()
                        .message(userMessage)
                        .build();
                AssistantSkillResult result = knowledgeSkill.execute(skillContext);
                return result.getResponseSummary() != null ? result.getResponseSummary() : "知识检索完成";
            } else if ("query_data".equals(action)) {
                return chatClient.prompt()
                        .user("请回答用户的数据查询问题：" + userMessage)
                        .call().content();
            } else {
                return chatClient.prompt()
                        .user(userMessage)
                        .call().content();
            }
        } catch (Exception e) {
            log.warn("Agent action failed: action={}, error={}", action, e.getMessage());
            return "执行失败: " + e.getMessage();
        }
    }
}
