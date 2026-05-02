package org.javaup.ai.assistant;

import org.javaup.ai.enums.ChatType;

public enum AssistantRouteType {
    BUSINESS("business", ChatType.ASSISTANT),
    KNOWLEDGE("knowledge", ChatType.MARKDOWN),
    GENERAL("general", ChatType.ASSISTANT),
    OPS("ops", ChatType.ANALYSIS);

    private final String code;
    private final ChatType legacyChatType;

    AssistantRouteType(String code, ChatType legacyChatType) {
        this.code = code;
        this.legacyChatType = legacyChatType;
    }

    public String getCode() {
        return code;
    }

    public ChatType getLegacyChatType() {
        return legacyChatType;
    }

    public static AssistantRouteType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (AssistantRouteType value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
