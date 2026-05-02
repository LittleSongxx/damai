package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalWhenFirstPassIsLowConfidence() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner();
        KnowledgeRetrievalEvaluator evaluator = mock(KnowledgeRetrievalEvaluator.class);
        KnowledgeRetrievalPlan plan = planner.plan("看看这个规则");
        RagSearchResultVo firstPass = RagSearchResultVo.builder()
                .rewrittenQuery("看看这个规则")
                .sources(List.of())
                .documents(List.of())
                .build();
        RagSearchResultVo corrected = RagSearchResultVo.builder()
                .rewrittenQuery("看看这个规则 看看这个规则")
                .sources(List.of())
                .documents(List.of())
                .build();
        StructuredRuleSupportService.SupportBundle supportBundle = new StructuredRuleSupportService.SupportBundle(List.of(), List.of());
        when(hybridSearchService.hybridSearchWithTrace("看看这个规则", 8, true)).thenReturn(firstPass);
        when(hybridSearchService.hybridSearchWithTrace("看看这个规则 看看这个规则", 8, true)).thenReturn(corrected);
        when(structuredRuleSupportService.lookup("看看这个规则")).thenReturn(supportBundle);
        when(evaluator.assess(firstPass, supportBundle.sources(), "none", plan))
                .thenReturn(new KnowledgeRetrievalAssessment(0.1D, "LOW", "none", List.of()));
        when(evaluator.assess(corrected, supportBundle.sources(), "query_decomposition", plan))
                .thenReturn(new KnowledgeRetrievalAssessment(0.6D, "MEDIUM", "query_decomposition", List.of()));
        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(hybridSearchService, structuredRuleSupportService, planner, evaluator);

        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertEquals(corrected, context.searchResult());
        assertEquals("query_decomposition", context.assessment().correctiveAction());
        verify(hybridSearchService, times(2)).hybridSearchWithTrace(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(8), org.mockito.ArgumentMatchers.eq(true));
    }
}
