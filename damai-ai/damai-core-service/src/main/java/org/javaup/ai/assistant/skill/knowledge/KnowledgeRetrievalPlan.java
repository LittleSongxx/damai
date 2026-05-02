package org.javaup.ai.assistant.skill.knowledge;

import java.util.List;

public record KnowledgeRetrievalPlan(
        String originalQuery,
        String normalizedQuery,
        int topK,
        boolean enableRerank,
        int evidenceSourceLimit,
        int evidenceSnippetLimit,
        int evidenceContextCharBudget,
        List<String> subQuestions
) {
}
