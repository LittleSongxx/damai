package org.javaup.ai.assistant.executor;

import org.javaup.ai.assistant.AssistantExecutionMode;

public interface AssistantExecutor {

    AssistantExecutionMode mode();

    void execute(AssistantExecutionContext context);
}
