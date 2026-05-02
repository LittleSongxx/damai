package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSearchResultVo;
import org.springframework.ai.document.Document;

import java.util.List;

public record KnowledgeRetrievalContext(
        KnowledgeRetrievalPlan plan,
        RagSearchResultVo searchResult,
        StructuredRuleSupportService.SupportBundle supportBundle,
        KnowledgeRetrievalAssessment assessment,
        List<Document> answerDocuments
) {
}
