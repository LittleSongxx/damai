package org.javaup.ai.assistant.eval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagEvalScorerTest {

    private final RagEvalScorer scorer = new RagEvalScorer();

    // ---- Context Recall Heuristic ----

    @Test
    void shouldDetectPerfectMatch() {
        List<Document> docs = List.of(
                new Document("退票需要登录购票账户并在订单详情页点击申请退票", Map.of("chunkId", "c1")),
                new Document("退票款项将在1-7个工作日内退回原支付账户", Map.of("chunkId", "c2"))
        );
        List<String> refs = List.of(
                "退票需要登录购票账户并在订单详情页点击申请退票",
                "退票款项将在1-7个工作日内退回原支付账户");

        double recall = scorer.computeContextRecallHeuristic(docs, refs);
        assertEquals(1.0, recall, 0.01);
    }

    @Test
    void shouldReturnZeroForEmptyDocs() {
        double recall = scorer.computeContextRecallHeuristic(List.of(), List.of("ref text"));
        assertEquals(0.0, recall);
    }

    @Test
    void shouldReturnZeroForEmptyRefs() {
        List<Document> docs = List.of(new Document("some text", Map.of()));
        double recall = scorer.computeContextRecallHeuristic(docs, List.of());
        assertEquals(0.0, recall);
    }

    @Test
    void shouldComputeRecallWithPartialOverlap() {
        List<Document> docs = List.of(
                new Document("退票需要登录账户申请", Map.of("chunkId", "c1"))
        );
        List<String> refs = List.of("退票需要登录账户申请", "not present in docs at all");

        double recall = scorer.computeContextRecallHeuristic(docs, refs);
        assertEquals(0.5, recall, 0.01);
    }

    @Test
    void shouldHandleNullInputs() {
        double recall = scorer.computeContextRecallHeuristic(null, null);
        assertEquals(0.0, recall);
    }

    // ---- LLM-based methods error handling ----

    @Test
    void generateAnswerShouldReturnFallbackForEmptyDocs() {
        String answer = scorer.generateAnswer("question", List.of());
        assertNotNull(answer);
        assertTrue(answer.contains("未检索到相关文档"));
    }

    @Test
    void evaluateContextShouldReturnZerosForEmptyDocs() {
        RagEvalScorer.ContextEvalResult result = scorer.evaluateContext(
                "question", "expected answer", List.of());
        assertEquals(0.0, result.contextPrecision());
        assertEquals(0.0, result.contextRecall());
        assertEquals(0.0, result.contextRelevance());
    }

    @Test
    void evaluateGenerationShouldReturnZerosForEmptyAnswer() {
        RagEvalScorer.GenEvalResult result = scorer.evaluateGeneration(
                "question", "", "expected answer", List.of());
        assertEquals(0.0, result.faithfulness());
        assertEquals(0.0, result.answerRelevancy());
        assertEquals(0.0, result.answerCorrectness());
    }
}
