package org.javaup.ai.assistant.memory;

public record AssistantMemoryContext(
        String summary,
        boolean present,
        AssistantStructuredMemory structuredMemory
) {

    public static AssistantMemoryContext empty() {
        return new AssistantMemoryContext("", false, AssistantStructuredMemory.empty());
    }
}
