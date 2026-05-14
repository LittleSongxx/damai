package org.javaup.ai.assistant.skill.knowledge;

import lombok.Builder;

import java.util.List;

@Builder
public record KnowledgeShadowRouteResult(
        String query,
        String mode,
        List<KnowledgeRouteCandidate> scopeCandidates,
        List<KnowledgeRouteCandidate> topicCandidates,
        List<KnowledgeRouteCandidate> documentCandidates
) {

    public static KnowledgeShadowRouteResult empty(String query) {
        return KnowledgeShadowRouteResult.builder()
                .query(query)
                .mode("disabled")
                .scopeCandidates(List.of())
                .topicCandidates(List.of())
                .documentCandidates(List.of())
                .build();
    }
}
