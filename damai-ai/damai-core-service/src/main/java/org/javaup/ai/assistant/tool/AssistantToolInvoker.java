package org.javaup.ai.assistant.tool;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AssistantToolInvoker {

    private final AssistantRunService assistantRunService;

    public <T> T invoke(String runId, String toolName, String toolType, Object input, AssistantToolCallable<T> callable) {
        assertToolAllowed(toolName);
        long start = System.currentTimeMillis();
        assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_STARTED, Map.of(
                "runId", runId,
                "toolName", toolName,
                "toolType", toolType
        ));
        try {
            T output = callable.call();
            long durationMs = System.currentTimeMillis() - start;
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
}
