package org.javaup.ai.enums;

import java.util.Arrays;

public enum CustomerServiceIntent {
    EVENT_SEARCH,
    PROGRAM_DETAIL,
    TICKET_CATEGORY,
    REFUND_RULE,
    REAL_NAME_RULE,
    ENTRY_RULE,
    ORDER_AFTERSALE,
    INVOICE,
    COMPLAINT,
    HUMAN_HANDOFF,
    GENERAL_CHAT;

    public static CustomerServiceIntent from(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL_CHAT;
        }
        String normalized = value.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(intent -> intent.name().equals(normalized))
                .findFirst()
                .orElse(GENERAL_CHAT);
    }
}
