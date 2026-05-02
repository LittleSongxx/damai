package org.javaup.ai.assistant;

public final class AssistantEventTypes {

    public static final String RUN_STARTED = "run.started";
    public static final String ROUTE_SELECTED = "route.selected";
    public static final String CLARIFICATION_REQUIRED = "clarification.required";
    public static final String SKILL_STARTED = "skill.started";
    public static final String SKILL_COMPLETED = "skill.completed";
    public static final String RETRIEVAL_STARTED = "retrieval.started";
    public static final String RETRIEVAL_COMPLETED = "retrieval.completed";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String ACTION_REQUIRED = "action.required";
    public static final String MESSAGE_DELTA = "message.delta";
    public static final String MESSAGE_COMPLETED = "message.completed";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";

    private AssistantEventTypes() {
    }
}
