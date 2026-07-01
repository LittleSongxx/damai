package org.javaup.ai.security;

public enum AccessDomain {
    CUSTOMER_SUPPORT(false),
    CUSTOMER_TICKETING(false),
    CUSTOMER_POLICY_QA(false),
    ADMIN_CUSTOMER_OPS(true),
    ADMIN_KNOWLEDGE_GOVERNANCE(true),
    ADMIN_OBSERVABILITY(true),
    ADMIN_DATA_QUERY(true),
    ADMIN_AI_GOVERNANCE(true),
    INTERNAL_DEV(true);

    private final boolean adminOnly;

    AccessDomain(boolean adminOnly) {
        this.adminOnly = adminOnly;
    }

    public boolean adminOnly() {
        return adminOnly;
    }
}
