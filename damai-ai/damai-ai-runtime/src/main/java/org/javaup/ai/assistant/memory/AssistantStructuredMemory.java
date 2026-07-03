package org.javaup.ai.assistant.memory;

import lombok.Builder;

import java.util.List;

@Builder
public record AssistantStructuredMemory(
        String summary,
        String conversationGoal,
        List<String> stableFacts,
        List<String> pendingQuestions,
        List<String> retrievalHints
) {

    public static AssistantStructuredMemory empty() {
        return AssistantStructuredMemory.builder()
                .summary("")
                .conversationGoal("")
                .stableFacts(List.of())
                .pendingQuestions(List.of())
                .retrievalHints(List.of())
                .build();
    }
}
