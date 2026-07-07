package org.javaup.ai.enums;

public enum EvaluationDomain {
    RAG_CUSTOMER,
    PURCHASE_AGENT,
    NL2SQL_ADMIN;

    public static EvaluationDomain from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Evaluation domain is required");
        }
        return EvaluationDomain.valueOf(value.trim().replace('-', '_').toUpperCase());
    }
}
