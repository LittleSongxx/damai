package org.javaup.ai.service;

public record FeedbackClusterInput(
        String routeType,
        String issueCategory,
        String userMessage,
        String aiAnswer,
        String feedbackComment
) {
}
