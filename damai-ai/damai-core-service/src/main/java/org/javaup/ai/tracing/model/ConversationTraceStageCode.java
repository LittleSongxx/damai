package org.javaup.ai.tracing.model;

public enum ConversationTraceStageCode {
    INPUT_GUARD,
    PLANNING,
    MEMORY,
    REWRITE,
    ROUTE,
    SKILL_SELECTION,
    RETRIEVAL,
    ANSWER,
    TOOL_CALL,
    FINALIZE,
    RECOMMENDATION,
    OUTPUT_GUARD,
    ACTION
}
