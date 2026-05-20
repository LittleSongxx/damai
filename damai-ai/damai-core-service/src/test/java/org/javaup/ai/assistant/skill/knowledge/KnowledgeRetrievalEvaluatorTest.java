package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class KnowledgeRetrievalEvaluatorTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(chatClient);

    @Test
    void shouldCalculateHighConfidenceWhenDenseSparseOverlapAndEnoughEvidence() {
        KnowledgeRetrievalAssessment assessment = evaluator.assess(RagSearchResultVo.builder()
                        .sources(List.of(source("a", "faq", 0.9D), source("b", "faq", 0.8D), source("c", "rule", 0.7D), source("d", "rule", 0.6D)))
                        .denseSources(List.of(source("a", "faq", 1D), source("b", "faq", 0.8D)))
                        .sparseSources(List.of(source("a", "faq", 12D), source("b", "faq", 10D)))
                        .build(),
                List.of(),
                "none",
                plan());

        assertEquals("CORRECT", assessment.confidenceLevel());
        assertEquals(4, assessment.sources().size());
        assertEquals("HIGH", assessment.relevanceLevel());
        assertEquals("HIGH", assessment.coverageLevel());
    }

    @Test
    void shouldApplyEvidenceBudgetAndDeduplicateSources() {
        KnowledgeRetrievalAssessment assessment = evaluator.assess(RagSearchResultVo.builder()
                        .sources(List.of(source("a", "faq", 0.4D), source("a", "faq", 0.3D), source("b", "rule", 0.2D)))
                        .denseSources(List.of(source("a", "faq", 0.4D)))
                        .sparseSources(List.of(source("b", "rule", 2D)))
                        .build(),
                List.of(source("c", "structured_rule", 0.35D)),
                "query_decomposition",
                new KnowledgeRetrievalPlan("q", "q", 8, true, 2, 8, 4000, List.of("q"), KnowledgeRetrievalPlan.Complexity.MEDIUM));

        assertEquals("query_decomposition", assessment.correctiveAction());
        assertEquals(2, assessment.sources().size());
        assertTrue(assessment.sources().get(0).getSnippet().length() <= 8);
    }

    private KnowledgeRetrievalPlan plan() {
        return new KnowledgeRetrievalPlan("q", "q", 8, true, 6, 260, 4000, List.of("q"), KnowledgeRetrievalPlan.Complexity.MEDIUM);
    }

    private RagSourceVo source(String chunkId, String source, Double score) {
        return RagSourceVo.builder()
                .chunkId(chunkId)
                .title("title-" + chunkId)
                .source(source)
                .section("section")
                .snippet("0123456789abcdefghijklmnopqrstuvwxyz")
                .score(score)
                .build();
    }
}
