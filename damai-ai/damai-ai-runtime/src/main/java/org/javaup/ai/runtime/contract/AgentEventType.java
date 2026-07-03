package org.javaup.ai.runtime.contract;

public enum AgentEventType {
    RUN_CREATED,
    ROUTE_DECIDED,
    MODEL_DELTA,
    TOOL_STARTED,
    TOOL_FINISHED,
    ARTIFACT_CREATED,
    APPROVAL_REQUIRED,
    CHECKPOINT_SAVED,
    RUN_SUCCEEDED,
    RUN_FAILED
}
