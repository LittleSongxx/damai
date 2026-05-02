package org.javaup.ai.assistant.tool;

import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssistantToolInvokerTest {

    @Test
    void shouldRecordSuccessfulToolInvocation() {
        AssistantRunService runService = mock(AssistantRunService.class);
        AssistantToolInvoker invoker = new AssistantToolInvoker(runService);

        String result = invoker.invoke("run_1", "toolA", "ops", "input", () -> "output");

        assertEquals("output", result);
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.TOOL_STARTED), any());
        verify(runService).recordToolCall(eq("run_1"), eq("toolA"), eq("ops"), eq("input"), eq("output"), any(Long.class), eq(true), eq(null));
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.TOOL_COMPLETED), any());
    }

    @Test
    void shouldRecordFailedToolInvocationAndRethrow() {
        AssistantRunService runService = mock(AssistantRunService.class);
        AssistantToolInvoker invoker = new AssistantToolInvoker(runService);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                invoker.invoke("run_1", "toolA", "ops", "input", () -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertEquals("boom", exception.getMessage());
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.TOOL_STARTED), any());
        verify(runService).recordToolCall(eq("run_1"), eq("toolA"), eq("ops"), eq("input"), eq(null), any(Long.class), eq(false), eq("boom"));
        verify(runService).appendEvent(eq("run_1"), eq(AssistantEventTypes.TOOL_COMPLETED), any());
    }
}
