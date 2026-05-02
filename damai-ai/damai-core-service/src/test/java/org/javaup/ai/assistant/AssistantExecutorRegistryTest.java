package org.javaup.ai.assistant;

import org.javaup.ai.assistant.executor.AssistantExecutionContext;
import org.javaup.ai.assistant.executor.AssistantExecutor;
import org.javaup.ai.assistant.executor.AssistantExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantExecutorRegistryTest {

    @Test
    void shouldResolveExecutorByExecutionMode() {
        AssistantExecutor skillExecutor = executor(AssistantExecutionMode.SKILL);
        AssistantExecutor clarificationExecutor = executor(AssistantExecutionMode.CLARIFICATION);
        AssistantExecutorRegistry registry = new AssistantExecutorRegistry(List.of(skillExecutor, clarificationExecutor));

        assertSame(skillExecutor, registry.getRequired(AssistantExecutionMode.SKILL));
        assertSame(clarificationExecutor, registry.getRequired(AssistantExecutionMode.CLARIFICATION));
    }

    @Test
    void shouldRejectDuplicateExecutorMode() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new AssistantExecutorRegistry(List.of(
                        executor(AssistantExecutionMode.SKILL),
                        executor(AssistantExecutionMode.SKILL)
                ))
        );

        assertEquals("duplicate assistant executor mode: SKILL", exception.getMessage());
    }

    private AssistantExecutor executor(AssistantExecutionMode mode) {
        return new AssistantExecutor() {
            @Override
            public AssistantExecutionMode mode() {
                return mode;
            }

            @Override
            public void execute(AssistantExecutionContext context) {
            }
        };
    }
}
