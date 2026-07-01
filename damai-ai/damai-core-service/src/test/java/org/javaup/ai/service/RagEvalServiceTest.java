package org.javaup.ai.service;

import org.javaup.ai.config.EvalConfig;
import org.javaup.ai.assistant.eval.RagEvalScorer;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalAssessment;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalContext;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalOrchestrator;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlan;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlanner;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalServiceTest {

    @Mock
    private AiRagEvalCaseMapper caseMapper;

    @Mock
    private AiRagEvalRunMapper runMapper;

    @Mock
    private AiRagEvalResultMapper resultMapper;

    @Mock
    private RagRetrievalFacade retrievalFacade;

    @Mock
    private RagEvalScorer ragEvalScorer;

    @Mock
    private RagChunkMapper ragChunkMapper;

    private RagEvalService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RagEvalService(caseMapper, runMapper, resultMapper, retrievalFacade, ragEvalScorer, ragChunkMapper, new EvalConfig());
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

    @Test
    void shouldReportPassWhenAllQualityGateMetricsMeetThreshold() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setAvgRecall(0.90);
        run.setAvgMrr(0.80);
        run.setAvgNdcg(0.85);
        run.setAvgCtxPrecision(0.80);
        run.setAvgCtxRecall(0.80);
        run.setAvgContextRelevance(0.75);
        run.setAvgFaithfulness(0.95);
        run.setAvgAnswerRelevancy(0.85);
        run.setAvgAnswerCorrectness(0.80);

        var gate = service.buildQualityGate(run);

        assertEquals("PASS", gate.get("status"));
        assertEquals(9L, gate.get("passedMetrics"));
    }

    @Test
    void shouldReportFailWhenMostQualityGateMetricsMissThreshold() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setAvgRecall(0.40);
        run.setAvgMrr(0.30);
        run.setAvgNdcg(0.45);
        run.setAvgCtxPrecision(0.50);
        run.setAvgCtxRecall(0.40);
        run.setAvgContextRelevance(0.45);
        run.setAvgFaithfulness(0.55);
        run.setAvgAnswerRelevancy(0.50);
        run.setAvgAnswerCorrectness(0.40);

        var gate = service.buildQualityGate(run);

        assertEquals("FAIL", gate.get("status"));
        assertEquals(0L, gate.get("passedMetrics"));
    }

    @Test
    void shouldPreviewEvalSelectionAndWarnings() {
        AiRagEvalCase refundCase = evalCase("case-refund", "退票规则", "faq", "easy", "[\"chunk-1\"]", "支持退票");
        AiRagEvalCase changeCase = evalCase("case-change", "改签规则", "faq", "hard", null, null);
        when(caseMapper.selectList(any())).thenReturn(List.of(changeCase, refundCase));

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setCaseIds(List.of("case-refund", "missing-case"));
        request.setLimit(1);

        Map<String, Object> preview = service.previewEvaluation(request);

        assertEquals(true, preview.get("canRun"));
        assertEquals(1, preview.get("selectedCases"));
        assertEquals(2, preview.get("matchedCasesBeforeLimit"));
        assertEquals(List.of("missing-case"), preview.get("missingCaseIds"));
        assertEquals(3L, preview.get("estimatedLlmCalls"));
        assertTrue(((List<?>) preview.get("warnings")).stream().anyMatch(item -> String.valueOf(item).contains("missing-case")));
        Map<?, ?> requestSummary = (Map<?, ?>) preview.get("request");
        assertEquals(5, requestSummary.get("topK"));
        assertEquals(true, requestSummary.get("enableRerank"));
    }

    @Test
    void shouldApplyCategoryDifficultyAndLimitInPreview() {
        AiRagEvalCase refundEasy = evalCase("case-1", "退票规则", "faq", "easy", "[\"chunk-1\"]", "支持退票");
        AiRagEvalCase refundEasySecond = evalCase("case-2", "退票时效", "faq", "easy", "[\"chunk-2\"]", "48小时内");
        when(caseMapper.selectList(any())).thenReturn(List.of(refundEasy, refundEasySecond));

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setCategory("faq");
        request.setDifficulty("easy");
        request.setLimit(1);
        request.setTopK(3);
        request.setEnableRerank(false);

        Map<String, Object> preview = service.previewEvaluation(request);

        assertEquals(1, preview.get("selectedCases"));
        assertEquals(2, preview.get("matchedCasesBeforeLimit"));
        Map<?, ?> categoryBreakdown = (Map<?, ?>) preview.get("categoryBreakdown");
        assertEquals(1L, categoryBreakdown.get("faq"));
        Map<?, ?> difficultyBreakdown = (Map<?, ?>) preview.get("difficultyBreakdown");
        assertEquals(1L, difficultyBreakdown.get("easy"));
        Map<?, ?> requestSummary = (Map<?, ?>) preview.get("request");
        assertEquals(3, requestSummary.get("topK"));
        assertEquals(false, requestSummary.get("enableRerank"));
    }

    @Test
    void shouldDiagnoseZeroOverlapAndMissingExpectedChunks() {
        AiRagEvalCase evalCase = evalCase("case-diagnose", "如何申请退票？", "refund", "easy", "[\"chunk-1\",\"chunk-2\"]", "支持退票");
        when(caseMapper.selectOne(any())).thenReturn(evalCase);
        when(retrievalFacade.retrieve(anyString(), anyInt(), anyBoolean())).thenReturn(RagSearchResultVo.builder()
                .sources(List.of(
                        RagSourceVo.builder().chunkId("chunk-3").score(0.9).build(),
                        RagSourceVo.builder().chunkId("chunk-4").score(0.8).build()))
                .documents(List.of(
                        new Document("当前检索结果1", Map.of("chunkId", "chunk-3")),
                        new Document("当前检索结果2", Map.of("chunkId", "chunk-4"))))
                .build());
        when(ragChunkMapper.selectByChunkUid("chunk-1")).thenReturn(chunk("chunk-1", 101L, "faq", "退款/退票", "退票规则正文"));
        when(ragChunkMapper.selectByChunkUid("chunk-2")).thenReturn(null);
        when(ragChunkMapper.selectByChunkUid("chunk-3")).thenReturn(chunk("chunk-3", 102L, "faq", "退票流程", "当前命中的退票流程正文"));
        when(ragChunkMapper.selectByChunkUid("chunk-4")).thenReturn(chunk("chunk-4", 103L, "faq", "退票说明", "当前命中的退票说明正文"));

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setTopK(5);
        request.setEnableRerank(true);

        Map<String, Object> diagnosis = service.diagnoseCase("case-diagnose", request);

        assertEquals("rag_retrieval_facade_fallback", diagnosis.get("retrievalPath"));
        assertEquals(2, diagnosis.get("expectedChunkCount"));
        assertEquals(1, diagnosis.get("missingExpectedChunkCount"));
        assertEquals(0, diagnosis.get("overlapAt5Count"));
        assertEquals(0.0, diagnosis.get("recallAt5"));
        assertEquals("ZERO_OVERLAP_WITH_CURRENT_RETRIEVAL", diagnosis.get("suspectedRootCause"));
        assertTrue(String.valueOf(diagnosis.get("missingExpectedChunkIds")).contains("chunk-2"));
        assertTrue(String.valueOf(diagnosis.get("warnings")).contains("zero overlap"));
    }

    @Test
    void shouldReturnNullWhenDiagnosingMissingCase() {
        when(caseMapper.selectOne(any())).thenReturn(null);

        assertNull(service.diagnoseCase("missing-case", new RagEvalRunRequest()));
    }

    @Test
    void shouldMarkRunFailedWhenResultInsertFails() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("run-failed");
        run.setTotalCases(1);
        run.setCompletedCases(0);

        AiRagEvalCase evalCase = evalCase("case-failed", "如何申请退票？", "refund", "easy", "[\"chunk-1\"]", "支持退票");
        when(retrievalFacade.retrieve(anyString(), anyInt(), anyBoolean())).thenReturn(searchResult());
        when(ragEvalScorer.generateAnswer(anyString(), anyList())).thenReturn("支持退票");
        when(ragEvalScorer.evaluateContext(anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.ContextEvalResult(0.8, 0.7, 0.6,
                        "{\"context_relevance_score\":0.6,\"coverage_score\":0.7,\"answerability\":\"ANSWERABLE\"}"));
        when(ragEvalScorer.evaluateGeneration(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.GenEvalResult(0.9, 0.85, 0.8,
                        0.1, 9, 1, 0.7, 0.8, 0.75, 0.82, 1.0, 0.95,
                        "{\"contradiction_score\":0.1,\"citation_support_score\":0.82,\"refusal_reason\":\"\"}"));
        when(resultMapper.insert(any(AiRagEvalResult.class))).thenThrow(new RuntimeException("Data too long for column 'eval_method'"));
        when(runMapper.updateById(any(AiRagEvalRun.class))).thenReturn(1);

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setTopK(5);
        request.setEnableRerank(false);

        service.executeEvalAsync(run, List.of(evalCase), request);

        assertEquals(0, run.getCompletedCases());
        assertEquals("FAILED", run.getRunStatus());
        assertEquals(0.0, run.getAvgRecall());
        assertEquals(0.0, run.getAvgPrecision());
        assertEquals(0.0, run.getAvgMrr());
        assertNull(run.getAvgFaithfulness());
        assertNull(run.getAvgAnswerCorrectness());
        assertTrue(run.getErrorMessage().contains("case-failed"));
        assertTrue(run.getErrorMessage().contains("Data too long"));
    }

    @Test
    void shouldPersistCompactEvalMethodAndCountCompletedCase() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("run-success");
        run.setTotalCases(1);
        run.setCompletedCases(0);

        AiRagEvalCase evalCase = evalCase("case-success", "如何申请退票？", "refund", "easy", "[\"chunk-1\"]", "支持退票");
        when(retrievalFacade.retrieve(anyString(), anyInt(), anyBoolean())).thenReturn(searchResult());
        when(ragEvalScorer.generateAnswer(anyString(), anyList())).thenReturn("支持退票");
        when(ragEvalScorer.evaluateContext(anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.ContextEvalResult(0.8, 0.7, 0.6,
                        "{\"context_relevance_score\":0.6,\"coverage_score\":0.7,\"answerability\":\"ANSWERABLE\"}"));
        when(ragEvalScorer.evaluateGeneration(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.GenEvalResult(0.9, 0.85, 0.8,
                        0.1, 9, 1, 0.7, 0.8, 0.75, 0.82, 1.0, 0.95,
                        "{\"contradiction_score\":0.1,\"citation_support_score\":0.82,\"refusal_reason\":\"\"}"));
        when(resultMapper.insert(any(AiRagEvalResult.class))).thenReturn(1);
        when(runMapper.updateById(any(AiRagEvalRun.class))).thenReturn(1);

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setTopK(5);
        request.setEnableRerank(false);

        service.executeEvalAsync(run, List.of(evalCase), request);

        ArgumentCaptor<AiRagEvalResult> captor = ArgumentCaptor.forClass(AiRagEvalResult.class);
        verify(resultMapper).insert(captor.capture());
        AiRagEvalResult persisted = captor.getValue();

        assertEquals("RAGAS_LLM_JUDGE_K5_NO_RR", persisted.getEvalMethod());
        assertTrue(persisted.getEvalMethod().length() <= 32);
        assertEquals(1, run.getCompletedCases());
        assertEquals("COMPLETED", run.getRunStatus());
        assertEquals(1.0, run.getAvgRecall());
        assertEquals(0.9, run.getAvgFaithfulness());
        assertEquals(0.8, run.getAvgAnswerCorrectness());
        assertEquals(0.6, persisted.getJudgeRelevance());
        assertEquals(0.7, persisted.getJudgeCoverage());
        assertEquals(0.1, persisted.getJudgeContradiction(), 0.0001);
        assertEquals(0.82, persisted.getJudgeCitationSupport());
        assertEquals(1.0, persisted.getJudgeAnswerability());
        assertTrue(persisted.getJudgeStructuredOutput().contains("citationSupport"));
        assertNull(run.getErrorMessage());
    }

    @Test
    void shouldPreferProductionRetrievalPathWhenAvailable() {
        ApplicationContext applicationContext = org.mockito.Mockito.mock(ApplicationContext.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeRetrievalPlanner> plannerProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeRetrievalOrchestrator> orchestratorProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        KnowledgeRetrievalPlanner planner = org.mockito.Mockito.mock(KnowledgeRetrievalPlanner.class);
        KnowledgeRetrievalOrchestrator orchestrator = org.mockito.Mockito.mock(KnowledgeRetrievalOrchestrator.class);

        service.setApplicationContext(applicationContext);
        when(applicationContext.getBeanProvider(KnowledgeRetrievalPlanner.class)).thenReturn(plannerProvider);
        when(applicationContext.getBeanProvider(KnowledgeRetrievalOrchestrator.class)).thenReturn(orchestratorProvider);
        when(plannerProvider.getIfAvailable()).thenReturn(planner);
        when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);

        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("run-prod-path");
        run.setTotalCases(1);
        run.setCompletedCases(0);

        AiRagEvalCase evalCase = evalCase("case-prod", "如何申请退票？", "refund", "easy", "[\"chunk-1\"]", "支持退票");
        KnowledgeRetrievalPlan basePlan = new KnowledgeRetrievalPlan(
                evalCase.getQuestion(), evalCase.getQuestion(), 10, true, 6, 260, 4000,
                List.of(evalCase.getQuestion()), KnowledgeRetrievalPlan.Complexity.MEDIUM);
        RagSourceVo source = RagSourceVo.builder().chunkId("chunk-1").score(0.95).build();
        Document document = new Document("支持退票，需按项目规则处理。", Map.of("chunkId", "chunk-1"));
        KnowledgeRetrievalContext retrievalContext = new KnowledgeRetrievalContext(
                basePlan,
                RagSearchResultVo.builder().sources(List.of(source)).documents(List.of(document)).build(),
                null,
                new KnowledgeRetrievalAssessment(0.9, "CORRECT", "none", List.of(source), "HIGH", "HIGH", false, "ANSWERABLE", ""),
                List.of(document));

        when(planner.plan(evalCase.getQuestion())).thenReturn(basePlan);
        when(orchestrator.retrieve(any(KnowledgeRetrievalPlan.class))).thenReturn(retrievalContext);
        when(ragEvalScorer.generateAnswer(anyString(), anyList())).thenReturn("支持退票");
        when(ragEvalScorer.evaluateContext(anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.ContextEvalResult(0.8, 0.7, 0.6));
        when(ragEvalScorer.evaluateGeneration(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new RagEvalScorer.GenEvalResult(0.9, 0.85, 0.8));
        when(resultMapper.insert(any(AiRagEvalResult.class))).thenReturn(1);
        when(runMapper.updateById(any(AiRagEvalRun.class))).thenReturn(1);

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setTopK(5);
        request.setEnableRerank(true);

        service.executeEvalAsync(run, List.of(evalCase), request);

        verify(orchestrator).retrieve(any(KnowledgeRetrievalPlan.class));
        verify(retrievalFacade, never()).retrieve(anyString(), anyInt(), anyBoolean());
        assertEquals(1, run.getCompletedCases());
        assertEquals("COMPLETED", run.getRunStatus());
        assertEquals(1.0, run.getAvgRecall());
    }

    private RagSearchResultVo searchResult() {
        return RagSearchResultVo.builder()
                .sources(List.of(RagSourceVo.builder().chunkId("chunk-1").score(0.9).build()))
                .documents(List.of(new Document("支持退票，需按项目规则处理。", Map.of("chunkId", "chunk-1"))))
                .build();
    }

    private RagChunk chunk(String chunkUid, Long docId, String chunkType, String headingPath, String text) {
        RagChunk chunk = new RagChunk();
        chunk.setChunkUid(chunkUid);
        chunk.setDocId(docId);
        chunk.setChunkType(chunkType);
        chunk.setHeadingPath(headingPath);
        chunk.setQuestion("问题");
        chunk.setText(text);
        chunk.setStatus(1);
        return chunk;
    }

    private AiRagEvalCase evalCase(String caseId, String question, String category, String difficulty,
                                   String expectedChunks, String expectedAnswer) {
        AiRagEvalCase evalCase = new AiRagEvalCase();
        evalCase.setCaseId(caseId);
        evalCase.setQuestion(question);
        evalCase.setCategory(category);
        evalCase.setDifficulty(difficulty);
        evalCase.setExpectedChunks(expectedChunks);
        evalCase.setExpectedAnswer(expectedAnswer);
        evalCase.setCreateTime(new Date());
        evalCase.setStatus(1);
        return evalCase;
    }
}
