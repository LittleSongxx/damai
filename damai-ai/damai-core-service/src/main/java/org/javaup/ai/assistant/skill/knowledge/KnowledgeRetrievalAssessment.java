package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSourceVo;

import java.util.List;

public record KnowledgeRetrievalAssessment(
        Double confidenceScore,
        String confidenceLevel,
        String correctiveAction,
        List<RagSourceVo> sources,
        String relevanceLevel,
        String coverageLevel,
        boolean hasContradictions,
        String answerabilityLevel,
        String missingInfo,
        List<String> verifiedClaims
) {
    public KnowledgeRetrievalAssessment(Double confidenceScore, String confidenceLevel,
                                         String correctiveAction, List<RagSourceVo> sources,
                                         String relevanceLevel, String coverageLevel,
                                         boolean hasContradictions, String answerabilityLevel,
                                         String missingInfo) {
        this(confidenceScore, confidenceLevel, correctiveAction, sources,
                relevanceLevel, coverageLevel, hasContradictions, answerabilityLevel,
                missingInfo, List.of());
    }
}
