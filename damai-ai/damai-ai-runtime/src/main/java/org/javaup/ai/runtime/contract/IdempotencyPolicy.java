package org.javaup.ai.runtime.contract;

public enum IdempotencyPolicy {
    NONE,
    REQUIRED,
    REQUIRED_PER_USER,
    REQUIRED_PER_RUN,
    REQUIRED_PER_ARTIFACT
}
