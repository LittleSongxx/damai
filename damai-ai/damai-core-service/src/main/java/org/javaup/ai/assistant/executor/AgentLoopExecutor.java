package org.javaup.ai.assistant.executor;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantExecutionMode;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.config.AgentLoopProperties;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.tracing.AiSpanService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
    private final AssistantSkillRegistry skillRegistry;
    private final AssistantMessageEmitter messageEmitter;
    private final AiSpanService spanService;
    private final ChatClient chatClient;
    private final List<ToolCallback> toolCallbacks;

    public AgentLoopExecutor(AgentLoopProperties properties,
                             AssistantRunService runService,
                             AssistantSkillRegistry skillRegistry,
                             AssistantMessageEmitter messageEmitter,
                             AiSpanService spanService,
                             @Qualifier("unifiedChatClient") ChatClient baseChatClient,
                             ChatModel chatModel,
                             List<ToolCallback> toolCallbacks) {
        this.properties = properties;
        this.runService = runService;
        this.skillRegistry = skillRegistry;
        this.messageEmitter = messageEmitter;
        this.spanService = spanService;
        this.toolCallbacks = toolCallbacks;
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
        List<Map<String, Object>> stepHistory = new ArrayList<>();

        var rootSpan = spanService.startSpan("agent_loop");
        spanService.setRunAttributes(rootSpan, run.getRunId(), null, null);

        try {
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

            runService.markCompleted(run, "RESPONDED", "agent_loop_completed");
            spanService.endSpanSuccess(rootSpan);
        } catch (TimeoutException e) {
            log.error("Agent loop step timed out: runId={}", run.getRunId(), e);
            runService.markFailed(run, "TIMEOUT", "Agent step exceeded timeout of " + properties.getStepTimeoutMs() + "ms");
            spanService.endSpanError(rootSpan, e);
        } catch (Exception e) {
            log.error("Agent loop error: runId={}, error={}", run.getRunId(), e.getMessage(), e);
            runService.markFailed(run, "FAILED", e.getMessage());
            spanService.endSpanError(rootSpan, e);
        }
    }

    private ChatResponse callWithTimeout(String prompt) throws Exception {
        long timeoutMs = properties.getStepTimeoutMs() > 0 ? properties.getStepTimeoutMs() : 30000;
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() ->
                chatClient.prompt().user(prompt).call().chatResponse());
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private String buildPlanningPrompt(String userMessage, List<Map<String, Object>> history) {
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
