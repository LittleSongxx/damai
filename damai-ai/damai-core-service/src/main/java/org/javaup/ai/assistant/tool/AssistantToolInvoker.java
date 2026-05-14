package org.javaup.ai.assistant.tool;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.guardrails.GuardrailAuditService;
import org.javaup.ai.guardrails.GuardrailResult;
import org.javaup.ai.guardrails.ToolGuardrailService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AssistantToolInvoker {

    private final AssistantRunService assistantRunService;
    private final ToolGuardrailService toolGuardrailService;
    private final GuardrailAuditService guardrailAuditService;

    public <T> T invoke(String runId, String toolName, String toolType, Object input, AssistantToolCallable<T> callable) {
        assertToolAllowed(toolName);
        GuardrailResult inputGuardrail = toolGuardrailService.check(toolName, "input", input);
        if (inputGuardrail.getAction() != GuardrailResult.Action.PASS) {
            guardrailAuditService.publish(runId, "tool_input", inputGuardrail, safePreview(input));
            if (inputGuardrail.isBlocked()) {
                throw new IllegalStateException(inputGuardrail.getSanitizedContent() == null
                        ? "工具入参触发安全策略，已拦截。"
                        : inputGuardrail.getSanitizedContent());
            }
        }
        long start = System.currentTimeMillis();
        assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_STARTED, Map.of(
                "runId", runId,
                "toolName", toolName,
                "toolType", toolType
        ));
        try {
            T output = callable.call();
            long durationMs = System.currentTimeMillis() - start;
            GuardrailResult outputGuardrail = toolGuardrailService.check(toolName, "output", output);
            if (outputGuardrail.getAction() != GuardrailResult.Action.PASS) {
                guardrailAuditService.publish(runId, "tool_output", outputGuardrail, safePreview(output));
                if (outputGuardrail.isBlocked()) {
                    assistantRunService.recordToolCall(runId, toolName, toolType, input, null, durationMs, false, "tool output blocked by guardrail");
                    assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, completedPayload(runId, toolName, toolType, durationMs, "FAILED", "tool output blocked by guardrail"));
                    throw new IllegalStateException(outputGuardrail.getSanitizedContent() == null
                            ? "工具返回结果触发安全策略，已拦截。"
                            : outputGuardrail.getSanitizedContent());
                }
            }
            assistantRunService.recordToolCall(runId, toolName, toolType, input, output, durationMs, true, null);
            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, completedPayload(runId, toolName, toolType, durationMs, "COMPLETED", null));
            return output;
        } catch (RuntimeException ex) {
            long durationMs = System.currentTimeMillis() - start;
            assistantRunService.recordToolCall(runId, toolName, toolType, input, null, durationMs, false, ex.getMessage());
            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, completedPayload(runId, toolName, toolType, durationMs, "FAILED", ex.getMessage()));
            throw ex;
        }
    }

    private void assertToolAllowed(String toolName) {
        AssistantSkillToolScope.current().ifPresent(scope -> {
            if (!scope.allows(toolName)) {
                throw new IllegalStateException("工具 " + toolName + " 不在 Skill " + scope.skillId() + " 的白名单内");
            }
        });
    }

    private Map<String, Object> completedPayload(String runId, String toolName, String toolType, long durationMs, String status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("toolName", toolName);
        payload.put("toolType", toolType);
        payload.put("durationMs", durationMs);
        payload.put("status", status);
        if (message != null) {
            payload.put("message", message);
        }
        return payload;
    }

    private String safePreview(Object payload) {
        if (payload == null) {
            return null;
        }
        String preview = String.valueOf(payload);
        return preview.length() <= 240 ? preview : preview.substring(0, 240);
    }
}
