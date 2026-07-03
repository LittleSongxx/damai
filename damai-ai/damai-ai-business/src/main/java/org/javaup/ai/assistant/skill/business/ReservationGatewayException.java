package org.javaup.ai.assistant.skill.business;

public class ReservationGatewayException extends RuntimeException {

    private final FailureCategory failureCategory;
    private final boolean retriable;
    private final boolean unknownResult;

    public ReservationGatewayException(String message, FailureCategory failureCategory, boolean retriable, boolean unknownResult) {
        super(message);
        this.failureCategory = failureCategory;
        this.retriable = retriable;
        this.unknownResult = unknownResult;
    }

    public ReservationGatewayException(String message, Throwable cause, FailureCategory failureCategory, boolean retriable, boolean unknownResult) {
        super(message, cause);
        this.failureCategory = failureCategory;
        this.retriable = retriable;
        this.unknownResult = unknownResult;
    }

    public FailureCategory failureCategory() {
        return failureCategory;
    }

    public boolean retriable() {
        return retriable;
    }

    public boolean unknownResult() {
        return unknownResult;
    }
}
