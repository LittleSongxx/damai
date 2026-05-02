package org.javaup.ai.assistant.executor;

import org.javaup.ai.assistant.AssistantExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AssistantExecutorRegistry {

    private final Map<AssistantExecutionMode, AssistantExecutor> executorMap;

    public AssistantExecutorRegistry(List<AssistantExecutor> executors) {
        EnumMap<AssistantExecutionMode, AssistantExecutor> map = new EnumMap<>(AssistantExecutionMode.class);
        for (AssistantExecutor executor : executors) {
            AssistantExecutor previous = map.putIfAbsent(executor.mode(), executor);
            if (previous != null) {
                throw new IllegalStateException("duplicate assistant executor mode: " + executor.mode());
            }
        }
        this.executorMap = Map.copyOf(map);
    }

    public AssistantExecutor getRequired(AssistantExecutionMode mode) {
        AssistantExecutor executor = executorMap.get(mode);
        if (executor == null) {
            throw new IllegalStateException("assistant executor not found: " + mode);
        }
        return executor;
    }
}
