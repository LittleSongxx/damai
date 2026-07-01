package org.javaup.ai.assistant;

public final class AssistantEventTypes {

    public static final String RUN_STARTED = "run.started";
    public static final String RUN_RESUMED = "run.resumed";
    public static final String RUN_RECOVERY_PLANNED = "run.recovery_planned";
    public static final String RUN_REPLAY_REQUESTED = "run.replay_requested";
    public static final String CHECKPOINT_REPLAYED = "checkpoint.replayed";
    public static final String ROUTE_SELECTED = "route.selected";
    public static final String CLARIFICATION_REQUIRED = "clarification.required";
    public static final String SKILL_STARTED = "skill.started";
    public static final String SKILL_COMPLETED = "skill.completed";
    public static final String RETRIEVAL_STARTED = "retrieval.started";
    public static final String RETRIEVAL_COMPLETED = "retrieval.completed";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String ACTION_REQUIRED = "action.required";
    public static final String ACTION_PROCESSING = "action.processing";
    public static final String MESSAGE_DELTA = "message.delta";
    public static final String MESSAGE_REPLACED = "message.replaced";
    public static final String MESSAGE_COMPLETED = "message.completed";
    public static final String STAGE_STARTED = "stage.started";
    public static final String STAGE_COMPLETED = "stage.completed";
    public static final String STAGE_FAILED = "stage.failed";
    public static final String KNOWLEDGE_ROUTE_SHADOWED = "knowledge.route.shadowed";
    public static final String GUARDRAIL_WARN = "guardrail.warn";
    public static final String GUARDRAIL_TRIGGERED = "guardrail.triggered";
    public static final String AGENT_STEP = "agent.step";
    public static final String FEEDBACK_RECEIVED = "feedback.received";
    public static final String CUSTOMER_QUICK_ANSWER_HIT = "customer.quick_answer.hit";
    public static final String CUSTOMER_QUICK_ANSWER_MISS = "customer.quick_answer.miss";
    public static final String CUSTOMER_SENTIMENT_DETECTED = "customer.sentiment.detected";
    public static final String CUSTOMER_SUGGESTIONS_GENERATED = "customer.suggestions.generated";
    public static final String CUSTOMER_ESCALATION_CREATED = "customer.escalation.created";
    public static final String CUSTOMER_SERVICE_CARD = "customer.service.card";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";

    private AssistantEventTypes() {
    }
}
