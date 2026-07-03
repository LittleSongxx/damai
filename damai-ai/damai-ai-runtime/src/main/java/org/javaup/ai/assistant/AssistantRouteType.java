package org.javaup.ai.assistant;

public enum AssistantRouteType {
    BUSINESS("business"),
    KNOWLEDGE("knowledge"),
    GENERAL("general"),
    OPS("ops");

    private final String code;

    AssistantRouteType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
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
