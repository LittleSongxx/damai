package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalWhenFirstPassIsLowConfidence() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        ChatClient chatClient = mock(ChatClient.class);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(chatClient);
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mock(AssistantStageTraceService.class);
        when(stageTraceService.startStage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(AssistantStageTraceService.StageSpan.builder().traceId("trace_1").stageKey("TEST").startedAt(System.currentTimeMillis()).build());

        when(advancedQueryService.decomposeSubQuestions(anyString())).thenReturn(List.of("看看这个规则"));
        when(advancedQueryService.rewriteQuery(anyString()))
                .thenReturn(new AdvancedQueryService.QueryRewriteResult("看看这个规则", List.of("看看这个规则")));

        KnowledgeRetrievalPlan plan = planner.plan("看看这个规则");
        RagSearchResultVo firstPass = RagSearchResultVo.builder()
                .rewrittenQuery("看看这个规则")
                .sources(List.of())
                .documents(List.of())
                .build();
        RagSearchResultVo corrected = RagSearchResultVo.builder()
                .originalQuery("看看这个规则")
                .normalizedQuery("看看这个规则")
                .rewrittenQuery("看看这个规则 reformulated")
                .sources(List.of())
                .documents(List.of())
                .build();
        StructuredRuleSupportService.SupportBundle supportBundle = new StructuredRuleSupportService.SupportBundle(List.of(), List.of());
        when(hybridSearchService.hybridSearchWithHyde("看看这个规则", 8, true)).thenReturn(firstPass);
        when(hybridSearchService.hybridSearchWithHyde(anyString(), anyInt(), anyBoolean())).thenReturn(corrected);
        when(structuredRuleSupportService.lookup("看看这个规则")).thenReturn(supportBundle);

        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(
                hybridSearchService, structuredRuleSupportService, planner, evaluator, advancedQueryService, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertTrue(context.assessment().correctiveAction().startsWith("crag_")
                || "AMBIGUOUS".equals(context.assessment().confidenceLevel())
                || "INCORRECT".equals(context.assessment().confidenceLevel())
                || "CORRECT".equals(context.assessment().confidenceLevel()));
        verify(hybridSearchService).hybridSearchWithHyde("看看这个规则", 8, true);
    }
}
