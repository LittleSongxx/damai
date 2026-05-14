package org.javaup.ai.assistant.skill.knowledge;

import lombok.Builder;

import java.util.List;

@Builder
public record KnowledgeRouteCandidate(
        String name,
        Double score,
        List<String> reasons
) {
}
