package org.javaup.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagEvalServiceTest {

    @Mock
    private HybridSearchService hybridSearchService;

    private RagEvalService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagEvalService(null, null, null, hybridSearchService, null, null, null);
    }

    @Test
    void shouldCalculatePerfectRecall() {
        List<String> retrieved = List.of("chunk-1", "chunk-2", "chunk-3");
        List<String> expected = List.of("chunk-1", "chunk-2");

        double recall = service.calculateRecallAtK(retrieved, expected, 5);
        assertEquals(1.0, recall);
    }

    @Test
    void shouldCalculatePartialRecall() {
        List<String> retrieved = List.of("chunk-1", "chunk-4", "chunk-5");
        List<String> expected = List.of("chunk-1", "chunk-2", "chunk-3");

        double recall = service.calculateRecallAtK(retrieved, expected, 5);
        assertEquals(1.0 / 3.0, recall, 0.01);
    }

    @Test
    void shouldReturnOneForEmptyExpected() {
        double recall = service.calculateRecallAtK(List.of("chunk-1"), List.of(), 5);
        assertEquals(1.0, recall);
    }

    @Test
    void shouldCalculateMrrFirstPosition() {
        double mrr = service.calculateMrr(List.of("chunk-1", "chunk-2"), List.of("chunk-1"));
        assertEquals(1.0, mrr);
    }

    @Test
    void shouldCalculateMrrSecondPosition() {
        double mrr = service.calculateMrr(List.of("chunk-a", "chunk-1"), List.of("chunk-1"));
        assertEquals(0.5, mrr);
    }

    @Test
    void shouldReturnZeroMrrWhenNoMatch() {
        double mrr = service.calculateMrr(List.of("chunk-x"), List.of("chunk-1"));
        assertEquals(0.0, mrr);
    }

    @Test
    void shouldCalculatePerfectNdcg() {
        List<String> retrieved = List.of("chunk-1", "chunk-2");
        List<String> expected = List.of("chunk-1", "chunk-2");

        double ndcg = service.calculateNdcgAtK(retrieved, expected, 5);
        assertEquals(1.0, ndcg, 0.01);
    }

    @Test
    void shouldCalculateLowerNdcgForPartialRelevance() {
        List<String> retrieved = List.of("chunk-1", "chunk-x", "chunk-2");
        List<String> expected = List.of("chunk-1", "chunk-2");

        double ndcg = service.calculateNdcgAtK(retrieved, expected, 5);
        assertTrue(ndcg < 1.0, "expected ndcg < 1.0 but was " + ndcg);
        assertTrue(ndcg > 0.5, "expected ndcg > 0.5 but was " + ndcg);
    }

    @Test
    void shouldReturnZeroNdcgWhenNoMatch() {
        double ndcg = service.calculateNdcgAtK(List.of("chunk-x"), List.of("chunk-1"), 5);
        assertEquals(0.0, ndcg);
    }

    @Test
    void shouldParseChunkIdsFromJson() {
        List<String> ids = service.parseChunkIds("[\"chunk_refund_001\",\"chunk_refund_002\"]");
        assertEquals(2, ids.size());
        assertEquals("chunk_refund_001", ids.get(0).trim());
        assertEquals("chunk_refund_002", ids.get(1).trim());
    }

    @Test
    void shouldParseEmptyChunkIds() {
        List<String> ids = service.parseChunkIds("");
        assertTrue(ids.isEmpty());
    }
}
