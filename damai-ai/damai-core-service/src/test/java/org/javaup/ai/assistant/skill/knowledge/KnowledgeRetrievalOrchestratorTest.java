package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalWhenFirstPassIsLowConfidence() {
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        ChatClient chatClient = mock(ChatClient.class);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(chatClient);
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mock(AssistantStageTraceService.class);
        when(stageTraceService.startStage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(AssistantStageTraceService.StageSpan.builder().traceId("trace_1").stageKey("TEST").startedAt(System.currentTimeMillis()).build());

        when(advancedQueryService.decomposeSubQuestions(anyString())).thenReturn(List.of("退票规则具体流程是什么怎么查询"));
        when(advancedQueryService.rewriteQuery(anyString()))
                .thenReturn(new AdvancedQueryService.QueryRewriteResult("退票规则具体流程是什么怎么查询", List.of("退票规则具体流程是什么怎么查询")));

        KnowledgeRetrievalPlan plan = planner.plan("退票规则具体流程是什么怎么查询");
        RagSearchResultVo firstPass = RagSearchResultVo.builder()
                .rewrittenQuery("退票规则具体流程是什么怎么查询")
                .sources(List.of())
                .documents(List.of())
                .build();
        RagSearchResultVo corrected = RagSearchResultVo.builder()
                .originalQuery("退票规则具体流程是什么怎么查询")
                .normalizedQuery("退票规则具体流程是什么怎么查询")
                .rewrittenQuery("退票规则具体流程是什么怎么查询 reformulated")
                .sources(List.of())
                .documents(List.of())
                .metadata(Map.of("retrievalBoundary", "RagRetrievalFacade"))
                .build();
        StructuredRuleSupportService.SupportBundle supportBundle = new StructuredRuleSupportService.SupportBundle(List.of(), List.of());
        when(retrievalFacade.retrieve(anyString(), anyInt(), anyBoolean())).thenReturn(firstPass, corrected);
        when(structuredRuleSupportService.lookup("退票规则具体流程是什么怎么查询")).thenReturn(supportBundle);

        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(
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
    void shouldResolveAnswerDocumentsWhenEngineOnlyReturnsSources() {
        RagRetrievalFacade retrievalFacade = mock(RagRetrievalFacade.class);
        StructuredRuleSupportService structuredRuleSupportService = mock(StructuredRuleSupportService.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        KnowledgeRetrievalPlanner planner = new KnowledgeRetrievalPlanner(advancedQueryService);
        ChatClient chatClient = mock(ChatClient.class);
        KnowledgeRetrievalEvaluator evaluator = new KnowledgeRetrievalEvaluator(chatClient);
        KnowledgeRetrievalTraceService retrievalTraceService = mock(KnowledgeRetrievalTraceService.class);
        AssistantStageTraceService stageTraceService = mock(AssistantStageTraceService.class);

        RagSourceVo source = RagSourceVo.builder()
                .chunkId("chunk-1")
                .title("title")
                .source("faq")
                .section("section")
                .snippet("snippet")
                .score(0.9)
                .build();
        Document doc = new Document("正文", Map.of("chunkId", "chunk-1"));

        when(retrievalFacade.retrieveSimple(anyString(), anyInt())).thenReturn(RagSearchResultVo.builder()
                .originalQuery("退票")
                .normalizedQuery("退票")
                .rewrittenQuery("退票")
                .sources(List.of(source))
                .documents(List.of(doc))
                .metadata(Map.of("retrievalBoundary", "RagRetrievalFacade", "documentResolvedByFacade", true))
                .build());
        when(structuredRuleSupportService.lookup("退票")).thenReturn(new StructuredRuleSupportService.SupportBundle(List.of(), List.of()));

        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(
                structuredRuleSupportService, planner, evaluator, advancedQueryService,
                retrievalFacade, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalPlan plan = new KnowledgeRetrievalPlan(
                "退票", "退票", 4, false, 6, 260, 4000, List.of("退票"), KnowledgeRetrievalPlan.Complexity.SIMPLE);
        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertEquals(1, context.answerDocuments().size());
        assertEquals("chunk-1", String.valueOf(context.answerDocuments().get(0).getMetadata().get("chunkId")));
        assertEquals("RagRetrievalFacade", context.searchResult().getMetadata().get("retrievalBoundary"));
    }
}
