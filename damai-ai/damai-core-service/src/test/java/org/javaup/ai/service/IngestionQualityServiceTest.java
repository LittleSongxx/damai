package org.javaup.ai.service;

import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionQualityServiceTest {

    @Test
    void shouldUseRetrievalFacadeForCoverageChecks() {
        RagChunkMapper chunkMapper = mock(RagChunkMapper.class);
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        IngestionQualityService service = new IngestionQualityService(chunkMapper, retrievalFacade);

        when(chunkMapper.selectAllActiveChunks()).thenReturn(List.of(activeChunk("chunk-1")));
        when(retrievalFacade.retrieveSimple(anyString(), anyInt())).thenReturn(RagSearchResultVo.builder()
                .sources(List.of(RagSourceVo.builder().chunkId("chunk-1").score(0.91).build()))
                .metadata(Map.of("retrievalBoundary", "RagRetrievalFacade", "retrievalMode", "simple"))
                .build());

        Map<String, Object> report = service.runQualityReport();

        Map<?, ?> coverage = (Map<?, ?>) report.get("retrievalCoverage");
        assertEquals(20, coverage.get("totalTests"));
        assertEquals(20, coverage.get("covered"));
        assertEquals("100.0%", coverage.get("coverageRatio"));
        assertTrue(String.valueOf(coverage.get("details")).contains("RagRetrievalFacade"));
        verify(retrievalFacade).retrieveSimple("如何退票", 5);
    }

    private RagChunk activeChunk(String chunkUid) {
        RagChunk chunk = new RagChunk();
        chunk.setChunkUid(chunkUid);
        chunk.setChunkType("faq");
        chunk.setHeadingPath("退票/规则");
        chunk.setQuestion("如何退票");
        chunk.setText("用户可以在订单详情中申请退票，是否支持退票以具体演出规则为准。");
        chunk.setHypotheticalQuestionsJson("[\"怎么退票\"]");
        chunk.setEmbeddingCached(true);
        return chunk;
    }
}
