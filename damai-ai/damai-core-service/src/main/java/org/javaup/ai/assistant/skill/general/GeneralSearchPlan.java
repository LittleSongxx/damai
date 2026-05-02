package org.javaup.ai.assistant.skill.general;

public record GeneralSearchPlan(String query, boolean searchRequired, String reason) {
}
