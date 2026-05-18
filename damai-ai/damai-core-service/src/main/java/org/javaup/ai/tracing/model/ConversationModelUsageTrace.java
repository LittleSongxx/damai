package org.javaup.ai.tracing.model;

import lombok.Builder;

@Builder
public record ConversationModelUsageTrace(
        String traceId,
        String stageCode,
        String modelName,
        String requestType,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long durationMs,
        boolean cacheHit
) {
}
