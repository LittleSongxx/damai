package org.javaup.ai.service;

import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.RetrievalStrategyPolicy;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RagBenchmarkServiceTest {

    @Mock
    private AiRagEvalCaseMapper caseMapper;

    @Mock
    private RagRetrievalFacade retrievalFacade;

    private RagBenchmarkService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagBenchmarkService(caseMapper, retrievalFacade, new RetrievalStrategyPolicy());
    }

    @Test
    void shouldRunStrategyBenchmarkAndReturnQualityMetrics() {
        AiRagEvalCase evalCase = new AiRagEvalCase();
        evalCase.setCaseId("case-refund");
        evalCase.setQuestion("退票多久到账");
        evalCase.setCategory("退票退款");
        evalCase.setDatasetId("default-golden");
        evalCase.setDatasetVersion("v1");
        evalCase.setExpectedChunks("[\"chunk-refund\"]");

        when(caseMapper.selectList(any())).thenReturn(List.of(evalCase));
        when(retrievalFacade.retrieve(anyString(), any(RetrievalStrategy.class), any(KnowledgeRetrievalFilter.class)))
                .thenReturn(RagSearchResultVo.builder()
                        .sources(List.of(
                                RagSourceVo.builder()
                                        .chunkId("chunk-refund")
                                        .snippet("退款一般按支付渠道原路返回。")
                                        .score(0.9D)
                                        .build(),
                                RagSourceVo.builder()
                                        .chunkId("chunk-refund-window")
                                        .snippet("到账时间受支付渠道处理时效影响。")
                                        .score(0.8D)
                                        .build(),
                                RagSourceVo.builder()
                                        .chunkId("chunk-refund-parent")
                                        .snippet("退票退款问题需要展示规则来源。")
                                        .score(0.7D)
                                        .build()))
                        .metadata(Map.of("strategyProfile", "STANDARD_HYBRID"))
                        .build());

        Map<String, Object> result = service.runBenchmark(Map.of(
                "datasetId", "default-golden",
                "datasetVersion", "v1",
                "limit", 1,
                "profiles", List.of("STANDARD_HYBRID")
        ));

        assertNotNull(result.get("benchmarkRunId"));
        assertEquals(1, result.get("caseCount"));
        List<?> profiles = (List<?>) result.get("profiles");
        assertEquals(1, profiles.size());
        Map<?, ?> profile = (Map<?, ?>) profiles.get(0);
        assertEquals("STANDARD_HYBRID", profile.get("profile"));
        assertEquals(1.0, (Double) profile.get("avgRecall"), 0.0001);
        assertEquals(1.0, (Double) profile.get("avgHitRate"), 0.0001);
        assertEquals(1.0, (Double) profile.get("avgMrr"), 0.0001);
        assertEquals(0.0, (Double) profile.get("handoffRate"), 0.0001);
        assertEquals("PASS", ((Map<?, ?>) result.get("qualityGate")).get("status"));
    }

    @Test
    void shouldUseFallbackCustomerServiceCasesWhenDatasetIsEmpty() {
        when(caseMapper.selectList(any())).thenReturn(List.of());
        when(retrievalFacade.retrieve(anyString(), any(RetrievalStrategy.class), any(KnowledgeRetrievalFilter.class)))
                .thenReturn(RagSearchResultVo.builder()
                        .sources(List.of())
                        .metadata(Map.of())
                        .build());

        Map<String, Object> result = service.runBenchmark(Map.of(
                "limit", 2,
                "profiles", List.of("FAST_EXACT")
        ));

        assertEquals(2, result.get("caseCount"));
        List<?> profiles = (List<?>) result.get("profiles");
        Map<?, ?> profile = (Map<?, ?>) profiles.get(0);
        assertEquals("FAST_EXACT", profile.get("profile"));
        assertFalse(((List<?>) profile.get("cases")).isEmpty());
        assertTrue((Double) profile.get("handoffRate") > 0D);
    }
}
