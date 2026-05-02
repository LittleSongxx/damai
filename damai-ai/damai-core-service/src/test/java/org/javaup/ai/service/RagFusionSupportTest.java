package org.javaup.ai.service;

import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagFusionSupportTest {

    @Test
    void shouldPrioritizeChunksReturnedByDenseAndSparseSearchTogether() {
        List<RagSourceVo> dense = List.of(
                source("chunk-a", "A"),
                source("chunk-b", "B")
        );
        List<RagSourceVo> sparse = List.of(
                source("chunk-b", "B"),
                source("chunk-c", "C")
        );

        List<RagSourceVo> fused = RagFusionSupport.reciprocalRankFusion(dense, sparse, 3);

        assertEquals(3, fused.size());
        assertEquals("chunk-b", fused.get(0).getChunkId());
        assertEquals("chunk-a", fused.get(1).getChunkId());
        assertEquals("chunk-c", fused.get(2).getChunkId());
    }

    @Test
    void shouldLimitSourcesWithoutMutatingOrder() {
        List<RagSourceVo> limited = RagFusionSupport.limit(List.of(
                source("chunk-a", "A"),
                source("chunk-b", "B"),
                source("chunk-c", "C")
        ), 2);

        assertEquals(2, limited.size());
        assertEquals("chunk-a", limited.get(0).getChunkId());
        assertEquals("chunk-b", limited.get(1).getChunkId());
    }

    @Test
    void shouldApplyEvidenceBudgetWithDedupAndSnippetLimit() {
        List<RagSourceVo> budgeted = RagFusionSupport.evidenceBudget(List.of(
                source("chunk-a", "A", "1234567890"),
                source("chunk-a", "A duplicate", "duplicate"),
                source("chunk-b", "B", "abcdef"),
                source("chunk-c", "C", "unused")
        ), 2, 4);

        assertEquals(2, budgeted.size());
        assertEquals("chunk-a", budgeted.get(0).getChunkId());
        assertEquals("1234", budgeted.get(0).getSnippet());
        assertEquals("chunk-b", budgeted.get(1).getChunkId());
        assertEquals("abcd", budgeted.get(1).getSnippet());
    }

    private RagSourceVo source(String chunkId, String title) {
        return source(chunkId, title, "snippet");
    }

    private RagSourceVo source(String chunkId, String title, String snippet) {
        return RagSourceVo.builder()
                .chunkId(chunkId)
                .title(title)
                .source(title + ".md")
                .section("section")
                .snippet(snippet)
                .score(1.0)
                .build();
    }
}
