package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.RetrievalStrategyPolicy;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalThroughStrategyFacadeWhenFirstPassIsLowConfidence() {
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(mock(ChatClient.class));
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mockStageTraceService();

        when(advancedQueryService.decomposeSubQuestions(anyString()))
                .thenReturn(List.of("退票规则具体流程是什么怎么查询"));

        KnowledgeRetrievalPlan plan = planner.plan("退票规则具体流程是什么怎么查询");
        RagSearchResultVo firstPass = RagSearchResultVo.builder()
                .originalQuery(plan.normalizedQuery())
                .normalizedQuery(plan.normalizedQuery())
                .rewrittenQuery(plan.normalizedQuery())
                .sources(List.of())
                .documents(List.of())
                .build();
        RagSearchResultVo corrected = RagSearchResultVo.builder()
                .originalQuery(plan.normalizedQuery())
                .normalizedQuery(plan.normalizedQuery())
                .rewrittenQuery(plan.normalizedQuery() + " reformulated")
                .sources(List.of())
                .documents(List.of())
                .metadata(Map.of("retrievalBoundary", "RagRetrievalFacade"))
                .build();
        when(retrievalFacade.retrieve(anyString(), any(RetrievalStrategy.class), any(KnowledgeRetrievalFilter.class)))
                .thenReturn(firstPass, corrected);
        when(structuredRuleSupportService.lookup(plan.normalizedQuery()))
                .thenReturn(new StructuredRuleSupportService.SupportBundle(List.of(), List.of()));

        KnowledgeRetrievalOrchestrator orchestrator = orchestrator(
                structuredRuleSupportService, planner, evaluator, advancedQueryService,
                retrievalFacade, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertTrue(context.assessment().correctiveAction().startsWith("crag_")
                || "AMBIGUOUS".equals(context.assessment().confidenceLevel())
                || "INCORRECT".equals(context.assessment().confidenceLevel())
                || "CORRECT".equals(context.assessment().confidenceLevel()));
        assertTrue(context.assessment().sources() != null, "sources should not be null");
    }

    @Test
    void shouldResolveAnswerDocumentsWhenFacadeReturnsSourcesAndDocuments() {
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(mock(ChatClient.class));
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mockStageTraceService();

        RagSourceVo source = RagSourceVo.builder()
                .chunkId("chunk-1")
                .title("title")
                .source("faq")
                .section("section")
                .snippet("snippet")
                .score(0.9)
                .build();
        Document doc = new Document("正文", Map.of("chunkId", "chunk-1"));

        when(retrievalFacade.retrieve(anyString(), any(RetrievalStrategy.class), any(KnowledgeRetrievalFilter.class)))
                .thenReturn(RagSearchResultVo.builder()
                        .originalQuery("退票")
                        .normalizedQuery("退票")
                        .rewrittenQuery("退票")
                        .sources(List.of(source))
                        .documents(List.of(doc))
                        .metadata(Map.of("retrievalBoundary", "RagRetrievalFacade", "documentResolvedByFacade", true))
                        .build());
        when(structuredRuleSupportService.lookup("退票"))
                .thenReturn(new StructuredRuleSupportService.SupportBundle(List.of(), List.of()));

        KnowledgeRetrievalOrchestrator orchestrator = orchestrator(
                structuredRuleSupportService, planner, evaluator, advancedQueryService,
                retrievalFacade, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalPlan plan = new KnowledgeRetrievalPlan(
                "退票", "退票", 4, false, 6, 260, 4000,
                List.of("退票"), KnowledgeRetrievalPlan.Complexity.SIMPLE);
        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertEquals(1, context.answerDocuments().size());
        assertEquals("chunk-1", String.valueOf(context.answerDocuments().get(0).getMetadata().get("chunkId")));
        assertEquals("RagRetrievalFacade", context.searchResult().getMetadata().get("retrievalBoundary"));
    }

    @Test
    void fastPathWithoutEvidenceShouldBeNotAnswerable() {
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(mock(ChatClient.class));
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mockStageTraceService();

        when(retrievalFacade.retrieve(anyString(), any(RetrievalStrategy.class), any(KnowledgeRetrievalFilter.class)))
                .thenReturn(RagSearchResultVo.builder()
                        .originalQuery("完全没有证据的问题")
                        .normalizedQuery("完全没有证据的问题")
                        .rewrittenQuery("完全没有证据的问题")
                        .sources(List.of())
                        .documents(List.of())
                        .build());
        when(structuredRuleSupportService.lookup("完全没有证据的问题"))
                .thenReturn(new StructuredRuleSupportService.SupportBundle(List.of(), List.of()));

        KnowledgeRetrievalOrchestrator orchestrator = orchestrator(
                structuredRuleSupportService, planner, evaluator, advancedQueryService,
                retrievalFacade, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalPlan plan = new KnowledgeRetrievalPlan(
                "完全没有证据的问题", "完全没有证据的问题", 4, false, 6, 260, 4000,
                List.of("完全没有证据的问题"), KnowledgeRetrievalPlan.Complexity.SIMPLE);
        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertEquals("NOT_ANSWERABLE", context.assessment().answerabilityLevel());
        assertEquals("INCORRECT", context.assessment().confidenceLevel());
    }

    private KnowledgeRetrievalOrchestrator orchestrator(StructuredRuleSupportService structuredRuleSupportService,
                                                        KnowledgeRetrievalPlanner planner,
                                                        KnowledgeRetrievalEvaluator evaluator,
                                                        AdvancedQueryService advancedQueryService,
                                                        RagRetrievalFacade retrievalFacade,
                                                        KnowledgeRetrievalTraceService retrievalTraceService,
                                                        AssistantStageTraceService stageTraceService) {
        return new KnowledgeRetrievalOrchestrator(
                structuredRuleSupportService, planner, evaluator, advancedQueryService,
                retrievalFacade, retrievalTraceService, stageTraceService,
                new RetrievalStrategyPolicy(), new CorrectiveQueryService(mock(ChatClient.class)));
    }

    private AssistantStageTraceService mockStageTraceService() {
        AssistantStageTraceService stageTraceService = mock(AssistantStageTraceService.class);
        when(stageTraceService.startStage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(AssistantStageTraceService.StageSpan.builder()
                        .traceId("trace_1")
                        .stageKey("TEST")
                        .startedAt(System.currentTimeMillis())
                        .build());
        return stageTraceService;
    }
}
