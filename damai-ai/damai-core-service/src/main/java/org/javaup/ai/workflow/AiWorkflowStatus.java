package org.javaup.ai.workflow;

public final class AiWorkflowStatus {

    public static final String RUNNING = "RUNNING";
    public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";

    private AiWorkflowStatus() {
    }
}
