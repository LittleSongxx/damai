package org.javaup.ai.assistant.profile;

public record AssistantUserProfileContext(String summary, String preferenceTagsJson, boolean present) {

    public static AssistantUserProfileContext empty() {
        return new AssistantUserProfileContext("", "[]", false);
    }
}
