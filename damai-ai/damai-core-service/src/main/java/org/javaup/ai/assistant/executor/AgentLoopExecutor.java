package org.javaup.ai.assistant.executor;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.config.AgentLoopProperties;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.rag.prompt.PromptTemplateLoader;
import org.javaup.ai.tracing.AiSpanService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class AgentLoopExecutor implements AssistantExecutor {

    private final AgentLoopProperties properties;
    private final AssistantRunService runService;
    private final AssistantMessageEmitter messageEmitter;
    private final AiSpanService spanService;
    private final ChatClient chatClient;
    private final List<ToolCallback> toolCallbacks;
    private final PromptTemplateLoader templateLoader;

    public AgentLoopExecutor(AgentLoopProperties properties,
                             AssistantRunService runService,
                             AssistantMessageEmitter messageEmitter,
                             AiSpanService spanService,
                             @Qualifier("unifiedChatClient") ChatClient baseChatClient,
                             ChatModel chatModel,
                             List<ToolCallback> toolCallbacks,
                             PromptTemplateLoader templateLoader) {
        this.properties = properties;
        this.runService = runService;
        this.messageEmitter = messageEmitter;
        this.spanService = spanService;
        this.toolCallbacks = toolCallbacks;
        this.templateLoader = templateLoader;
        this.chatClient = toolCallbacks.isEmpty()
                ? baseChatClient
                : ChatClient.builder(chatModel)
                        .defaultToolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
                        .build();
    }

    @Override
    public AssistantExecutionMode mode() {
        return AssistantExecutionMode.AGENT_LOOP;
    }

    @Override
    public void execute(AssistantExecutionContext context) {
        AiRun run = context.getRun();
        String userMessage = context.getRequest().getMessage();

        var rootSpan = spanService.startSpan("agent_loop");
        spanService.setRunAttributes(rootSpan, run.getRunId(), null, null);

        try {
            String plan = generatePlan(userMessage);
            if (plan != null && !plan.isBlank()) {
                executePlan(run, userMessage, plan, rootSpan);
            } else {
                executeReActLoop(run, userMessage, rootSpan);
            }

            runService.markCompleted(run, "RESPONDED", "agent_loop_completed");
            spanService.endSpanSuccess(rootSpan);
        } catch (Exception e) {
            log.error("Agent loop error: runId={}, error={}", run.getRunId(), e.getMessage(), e);
            runService.markFailed(run, "FAILED", e.getMessage());
            spanService.endSpanError(rootSpan, e);
        }
    }

    private String generatePlan(String userMessage) {
        try {
            String prompt = String.format("""
                    你是一个任务规划器。根据用户请求，生成一个简洁的执行计划。
                    如果只需要一步即可完成，返回 "SINGLE_STEP"。
                    如果需要多步，每行一个步骤，格式为: [步骤号] [工具名] [参数描述]

                    可用工具：%s

                    用户请求：%s

                    计划：
                    """,
                    toolCallbacks.stream().map(t -> t.getToolDefinition().name()).toList(),
                    userMessage);
            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
            if (response != null && !response.getResults().isEmpty()) {
                String text = response.getResults().get(0).getOutput().getText();
                if (text != null && !text.isBlank() && !text.startsWith("SINGLE_STEP")) {
                    return text.trim();
                }
            }
        } catch (Exception e) {
            log.debug("Plan generation failed, falling back to ReAct: {}", e.getMessage());
        }
        return null;
    }

    private void executePlan(AiRun run, String userMessage, String plan, io.opentelemetry.api.trace.Span rootSpan) throws Exception {
        List<String> planSteps = plan.lines().filter(line -> line.matches("^\\[?\\d+\\]?\\s+\\w+.*")).toList();
        if (planSteps.isEmpty()) {
            executeReActLoop(run, userMessage, rootSpan);
            return;
        }

        Map<String, Object> planPayload = new HashMap<>();
        planPayload.put("step", 0);
        planPayload.put("action", "plan");
        planPayload.put("plan", planSteps);
        runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, planPayload);

        StringBuilder observations = new StringBuilder();
        int stepNum = 1;
        for (String planStep : planSteps) {
            if (stepNum > properties.getMaxSteps()) break;
            String toolName = extractToolName(planStep);
            if (toolName == null) continue;

            try {
                Map<String, Object> stepPayload = new HashMap<>();
                stepPayload.put("step", stepNum);
                stepPayload.put("action", "planned_tool");
                stepPayload.put("tool", toolName);

                String result = executeToolCall(toolName, "");
                observations.append("[").append(toolName).append("] ").append(result).append("\n");
                stepPayload.put("observation", result);
                runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, stepPayload);
                stepNum++;
            } catch (Exception e) {
                log.warn("Planned step failed, falling back to ReAct: tool={}, error={}", toolName, e.getMessage());
                try {
                    executeReActLoop(run, userMessage, rootSpan);
                } catch (Exception ex) {
                    log.error("ReAct fallback also failed", ex);
                }
                return;
            }
        }

        synthesizeFinalAnswer(run, userMessage, observations.toString());
    }

    private String extractToolName(String planStep) {
        for (ToolCallback callback : toolCallbacks) {
            String name = callback.getToolDefinition().name();
            if (planStep.contains(name)) return name;
        }
        String[] parts = planStep.trim().split("\\s+");
        return parts.length >= 2 ? parts[1] : null;
    }

    private void synthesizeFinalAnswer(AiRun run, String userMessage, String observations) {
        try {
            String prompt = String.format("""
                    根据以下工具执行结果，回答用户的问题。

                    用户问题：%s

                    工具执行结果：
                    %s

                    请给出完整、准确的回答：
                    """, userMessage, observations);
            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
            if (response != null && !response.getResults().isEmpty()) {
                String text = response.getResults().get(0).getOutput().getText();
                if (text != null && !text.isBlank()) {
                    messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), text);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Final answer synthesis failed: {}", e.getMessage());
        }
        String summary = "已完成多步分析，请查看上述结果。";
        messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), summary);
    }

    private void executeReActLoop(AiRun run, String userMessage, io.opentelemetry.api.trace.Span rootSpan) throws Exception {
        List<Map<String, Object>> stepHistory = new ArrayList<>();
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            var stepSpan = spanService.startSpan("agent_step_" + step, rootSpan);

            String prompt = buildPlanningPrompt(userMessage, stepHistory);
            ChatResponse response = callWithTimeout(prompt);

            Map<String, Object> stepPayload = new HashMap<>();
            stepPayload.put("step", step);

            if (response != null && response.hasToolCalls()) {
                AssistantMessage assistantMessage = response.getResult().getOutput();
                StringBuilder observation = new StringBuilder();
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    String result = executeToolCall(toolCall.name(), toolCall.arguments());
                    observation.append("[").append(toolCall.name()).append("] ").append(result).append("\n");
                }
                stepPayload.put("action", "tool_calls");
                stepPayload.put("observation", observation.toString().trim());
                stepHistory.add(stepPayload);
            } else if (response != null && !response.getResults().isEmpty()) {
                var output = response.getResults().get(0).getOutput();
                if (output != null) {
                    String text = output.getText();
                    if (text != null && !text.isBlank()) {
                        stepPayload.put("action", "final_answer");
                        stepPayload.put("result", text);
                        runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, stepPayload);
                        messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), text);
                        spanService.endSpanSuccess(stepSpan);
                        break;
                    }
                }
            }

            runService.appendEvent(run.getRunId(), AssistantEventTypes.AGENT_STEP, stepPayload);
            spanService.endSpanSuccess(stepSpan);

            if (step == properties.getMaxSteps()) {
                String summary = "我已经完成了多步分析，请查看上述结果。";
                messageEmitter.emitMessage(run.getRunId(), run.getConversationId(), summary);
            }
        }
    }

    private ChatResponse callWithTimeout(String prompt) throws Exception {
        long timeoutMs = properties.getStepTimeoutMs() > 0 ? properties.getStepTimeoutMs() : 30000;
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() ->
                chatClient.prompt().user(prompt).call().chatResponse());
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private String buildPlanningPrompt(String userMessage, List<Map<String, Object>> history) {
        if (templateLoader != null && templateLoader.hasTemplate("agent-loop.st")) {
            StringBuilder historyStr = new StringBuilder();
            if (!history.isEmpty()) {
                for (Map<String, Object> step : history) {
                    historyStr.append("Step ").append(step.get("step")).append(": ")
                            .append(step.get("action")).append(" -> ")
                            .append(step.get("observation")).append("\n");
                }
            }
            return templateLoader.render("agent-loop.st", Map.of(
                    "user_message", userMessage,
                    "history", historyStr.toString()
            ));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你是大麦票务平台的智能助手。请使用可用的工具逐步完成用户的请求。\n");
        sb.append("当你有足够信息回答用户时，直接给出最终答案。\n\n");
        sb.append("用户问题：").append(userMessage).append("\n\n");

        if (!history.isEmpty()) {
            sb.append("历史步骤：\n");
            for (Map<String, Object> step : history) {
                sb.append("Step ").append(step.get("step")).append(": ")
                        .append(step.get("action")).append(" -> ")
                        .append(step.get("observation")).append("\n");
            }
        }
        return sb.toString();
    }

    private String executeToolCall(String toolName, String arguments) {
        for (ToolCallback callback : toolCallbacks) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                try {
                    return callback.call(arguments);
                } catch (Exception e) {
                    log.warn("Tool call failed: tool={}, error={}", toolName, e.getMessage());
                    return "Tool execution failed: " + e.getMessage();
                }
            }
        }
        return "Unknown tool: " + toolName;
    }
}
