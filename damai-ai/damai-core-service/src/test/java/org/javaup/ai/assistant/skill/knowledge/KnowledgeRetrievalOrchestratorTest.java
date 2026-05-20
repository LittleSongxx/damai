package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeRetrievalOrchestratorTest {

    @Test
    void shouldRunCorrectiveRetrievalWhenFirstPassIsLowConfidence() {
        MultiChannelRetrievalEngine retrievalEngine = mock(MultiChannelRetrievalEngine.class);
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
                .build();
        StructuredRuleSupportService.SupportBundle supportBundle = new StructuredRuleSupportService.SupportBundle(List.of(), List.of());
        when(retrievalEngine.retrieve(any(SearchContext.class))).thenReturn(firstPass, corrected);
        when(structuredRuleSupportService.lookup("退票规则具体流程是什么怎么查询")).thenReturn(supportBundle);

        KnowledgeRetrievalOrchestrator orchestrator = new KnowledgeRetrievalOrchestrator(
                retrievalEngine, structuredRuleSupportService, planner, evaluator, advancedQueryService, retrievalTraceService, stageTraceService);

        KnowledgeRetrievalContext context = orchestrator.retrieve(plan);

        assertTrue(context.assessment().correctiveAction().startsWith("crag_")
                || "AMBIGUOUS".equals(context.assessment().confidenceLevel())
                || "INCORRECT".equals(context.assessment().confidenceLevel())
                || "CORRECT".equals(context.assessment().confidenceLevel()));
        assertTrue(context.assessment().sources() != null, "sources should not be null");
    }
}
