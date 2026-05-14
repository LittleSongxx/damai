package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalWhenFirstPassIsLowConfidence() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner();
        KnowledgeRetrievalEvaluator evaluator = mock(KnowledgeRetrievalEvaluator.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mock(AssistantStageTraceService.class);
        when(stageTraceService.startStage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(AssistantStageTraceService.StageSpan.builder().traceId("trace_1").stageKey("TEST").startedAt(System.currentTimeMillis()).build());

        KnowledgeRetrievalPlan plan = planner.plan("看看这个规则");
        RagSearchResultVo firstPass = RagSearchResultVo.builder()
                .rewrittenQuery("看看这个规则")
                .sources(List.of())
                .documents(List.of())
                .build();
        RagSearchResultVo corrected = RagSearchResultVo.builder()
                .originalQuery("看看这个规则")
                .normalizedQuery("看看这个规则")
                .rewrittenQuery("看看这个规则 看看这个规则")
                .sources(List.of())
                .documents(List.of())
                .build();
        StructuredRuleSupportService.SupportBundle supportBundle = new StructuredRuleSupportService.SupportBundle(List.of(), List.of());
        when(hybridSearchService.hybridSearchWithTrace("看看这个规则", 8, true)).thenReturn(firstPass);
        when(hybridSearchService.hybridSearchWithTrace(eq("看看这个规则 看看这个规则"), eq(8), eq(true))).thenReturn(corrected);
        when(hybridSearchService.hydeSearch(anyString(), anyInt())).thenReturn(List.of());
        when(structuredRuleSupportService.lookup("看看这个规则")).thenReturn(supportBundle);
        when(advancedQueryService.decomposeSubQuestions(anyString())).thenReturn(List.of("看看这个规则"));
        when(evaluator.assess(eq(firstPass), any(), eq("none"), eq(plan)))
                .thenReturn(new KnowledgeRetrievalAssessment(0.1D, "LOW", "none", List.of()));
        when(evaluator.assess(any(RagSearchResultVo.class), any(), anyString(), eq(plan)))
                .thenAnswer(invocation -> {
                    String action = invocation.getArgument(2);
                    if ("none".equals(action)) {
                        return new KnowledgeRetrievalAssessment(0.1D, "LOW", "none", List.of());
                    }
                    return new KnowledgeRetrievalAssessment(0.6D, "MEDIUM", action, List.of());
                });
        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(
                hybridSearchService, structuredRuleSupportService, planner, evaluator, advancedQueryService, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertTrue(context.assessment().correctiveAction().startsWith("crag_"));
        verify(hybridSearchService).hybridSearchWithTrace("看看这个规则", 8, true);
    }
}
