package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSourceVo;

import java.util.List;

public record KnowledgeRetrievalAssessment(
        Double confidenceScore,
        String confidenceLevel,
        String correctiveAction,
        List<RagSourceVo> sources
) {
}
