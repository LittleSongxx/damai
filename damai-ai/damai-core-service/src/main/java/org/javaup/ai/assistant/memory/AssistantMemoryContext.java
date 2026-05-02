package org.javaup.ai.assistant.memory;

public record AssistantMemoryContext(
        String summary,
        boolean present
) {

    public static AssistantMemoryContext empty() {
        return new AssistantMemoryContext("", false);
    }
}
