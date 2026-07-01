package org.javaup.ai.service;

import org.javaup.ai.entity.AiRagBadCase;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiRagBadCaseMapper;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.javaup.ai.vo.RagBadCaseConvertRequest;
import org.javaup.ai.vo.RagBadCaseReviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalOpsServicesTest {

    @Mock
    private AiRagEvalRunMapper runMapper;

    @Mock
    private AiRagEvalResultMapper resultMapper;

    @Mock
    private AiRagEvalCaseMapper caseMapper;

    @Mock
    private RagEvalService ragEvalService;

    @Mock
    private AiRagBadCaseMapper badCaseMapper;

    @Mock
    private RagOnlineTraceService traceService;

    private RagEvalReportService reportService;
    private RagEvalBaselineService baselineService;
    private RagBadCaseService badCaseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportService = new RagEvalReportService(runMapper, resultMapper, caseMapper, ragEvalService);
        baselineService = new RagEvalBaselineService(runMapper, resultMapper);
        badCaseService = new RagBadCaseService(badCaseMapper, caseMapper, traceService);
    }

    @Test
    void shouldBuildRunReportWithBreakdowns() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("run-report");
        run.setDatasetId("default-golden");
        run.setDatasetVersion("v1");
        run.setRunStatus("COMPLETED");
        run.setTotalCases(1);
        run.setCompletedCases(1);
        run.setAvgRecall(0.9);
        run.setAvgAnswerCorrectness(0.8);

        AiRagEvalResult result = new AiRagEvalResult();
        result.setEvalRunId("run-report");
        result.setCaseId("case-1");
        result.setQuestion("如何退票");
        result.setRecallAt5(0.9);
        result.setFaithfulnessScore(0.8);
        result.setAnswerCorrectnessScore(0.85);
        result.setJudgeRelevance(0.8);
        result.setJudgeCoverage(0.7);
        result.setJudgeContradiction(0.1);
        result.setJudgeCitationSupport(0.6);
        result.setJudgeAnswerability(1.0);
        result.setTotalLatencyMs(120L);
        result.setFailureType("citation_missing");

        AiRagEvalCase evalCase = new AiRagEvalCase();
        evalCase.setCaseId("case-1");
        evalCase.setCategory("faq");
        evalCase.setDifficulty("easy");
        evalCase.setCaseType("single_hop");

        when(runMapper.selectOne(any())).thenReturn(run);
        when(resultMapper.selectList(any())).thenReturn(List.of(result));
        when(caseMapper.selectList(any())).thenReturn(List.of(evalCase));
        when(ragEvalService.buildQualityGate(run)).thenReturn(Map.of("status", "PASS"));

        Map<String, Object> report = reportService.getRunReport("run-report");

        assertNotNull(report);
        assertEquals("run-report", report.get("evalRunId"));
        assertEquals("default-golden", report.get("datasetId"));
        assertEquals("PASS", ((Map<?, ?>) report.get("qualityGate")).get("status"));
        assertEquals(1L, ((Map<?, ?>) report.get("categoryBreakdown")).get("faq"));
        assertEquals(1L, ((Map<?, ?>) report.get("caseTypeBreakdown")).get("single_hop"));
        Map<?, ?> judgeSummary = (Map<?, ?>) report.get("structuredJudgeSummary");
        assertEquals(0.7, (Double) judgeSummary.get("avgCoverage"), 0.0001);
        assertEquals(0L, judgeSummary.get("contradictionRiskCount"));
        Map<?, ?> closurePlan = (Map<?, ?>) report.get("closurePlan");
        assertEquals(false, closurePlan.get("baselineReady"));
        assertEquals(true, closurePlan.get("releaseBlocked"));
        assertEquals(1L, closurePlan.get("failedCases"));
        assertEquals(1L, closurePlan.get("weakCitationCases"));
        assertTrue(String.valueOf(closurePlan.get("candidateEvalCaseIds")).contains("case-1"));
        assertTrue(String.valueOf(closurePlan.get("nextActions")).contains("baseline comparison"));
        assertTrue(String.valueOf(closurePlan.get("workflow")).contains("convert to eval"));
    }

    @Test
    void shouldCompareBaselineRunAndExposeMetricDiff() {
        AiRagEvalRun current = new AiRagEvalRun();
        current.setEvalRunId("run-current");
        current.setRunStatus("COMPLETED");
        current.setAvgRecall(0.9);
        current.setAvgFaithfulness(0.8);
        current.setAvgAnswerCorrectness(0.85);

        AiRagEvalRun baseline = new AiRagEvalRun();
        baseline.setEvalRunId("run-base");
        baseline.setRunStatus("COMPLETED");
        baseline.setAvgRecall(0.7);
        baseline.setAvgFaithfulness(0.75);
        baseline.setAvgAnswerCorrectness(0.80);

        AiRagEvalResult currentResult = new AiRagEvalResult();
        currentResult.setCaseId("case-1");
        currentResult.setQuestion("如何退票");
        currentResult.setRecallAt5(0.9);
        currentResult.setFaithfulnessScore(0.8);
        currentResult.setAnswerCorrectnessScore(0.9);

        AiRagEvalResult baselineResult = new AiRagEvalResult();
        baselineResult.setCaseId("case-1");
        baselineResult.setQuestion("如何退票");
        baselineResult.setRecallAt5(0.7);
        baselineResult.setFaithfulnessScore(0.7);
        baselineResult.setAnswerCorrectnessScore(0.8);

        when(runMapper.selectOne(any())).thenReturn(current, baseline);
        when(resultMapper.selectList(any())).thenReturn(List.of(currentResult), List.of(baselineResult));

        Map<String, Object> comparison = baselineService.compareRun("run-current", "run-base");

        assertNotNull(comparison);
        assertEquals(0.2, (Double) ((Map<?, ?>) comparison.get("metricDiff")).get("avgRecall"), 0.0001);
        assertEquals(1, ((Map<?, ?>) comparison.get("caseDiffs")).get("count"));
    }

    @Test
    void shouldConvertBadCaseToEvalCase() {
        AiRagBadCase badCase = new AiRagBadCase();
        badCase.setBadCaseId("bad-1");
        badCase.setQuestion("如何退票");
        badCase.setExpectedAnswer("支持退票");
        badCase.setExpectedChunks("[\"chunk-1\"]");
        badCase.setCategory("faq");
        badCase.setDifficulty("easy");
        badCase.setCaseType("single_hop");

        when(badCaseMapper.selectOne(any())).thenReturn(badCase);
        when(caseMapper.insert(any(AiRagEvalCase.class))).thenReturn(1);
        when(badCaseMapper.updateById(any(AiRagBadCase.class))).thenReturn(1);

        RagBadCaseConvertRequest request = new RagBadCaseConvertRequest();
        request.setDatasetId("golden-ds");
        request.setDatasetVersion("v2");
        request.setCategory("refund");

        AiRagEvalCase created = badCaseService.convertToEvalCase("bad-1", request);

        assertNotNull(created);
        assertEquals("golden-ds", created.getDatasetId());
        assertEquals("v2", created.getDatasetVersion());
        assertEquals("refund", created.getCategory());
        assertEquals("如何退票", created.getQuestion());
        assertEquals(1, badCase.getConvertedToEvalCase());
        assertEquals("CONVERTED", badCase.getReviewStatus());
        assertNotNull(badCase.getConvertedCaseId());

        ArgumentCaptor<AiRagEvalCase> captor = ArgumentCaptor.forClass(AiRagEvalCase.class);
        verify(caseMapper).insert(captor.capture());
        assertEquals("golden-ds", captor.getValue().getDatasetId());
    }

    @Test
    void shouldReviewBadCaseWithAuditFields() {
        AiRagBadCase badCase = new AiRagBadCase();
        badCase.setBadCaseId("bad-review");
        badCase.setReviewStatus("PENDING");

        when(badCaseMapper.selectOne(any())).thenReturn(badCase);
        when(badCaseMapper.updateById(any(AiRagBadCase.class))).thenReturn(1);

        RagBadCaseReviewRequest request = new RagBadCaseReviewRequest();
        request.setReviewStatus("FIXED");
        request.setReviewNote("prompt version v3 fixed citation support");

        AiRagBadCase reviewed = badCaseService.reviewBadCase("bad-review", request, 99L);

        assertNotNull(reviewed);
        assertEquals("FIXED", reviewed.getReviewStatus());
        assertEquals(99L, reviewed.getReviewedBy());
        assertNotNull(reviewed.getReviewedAt());
        assertEquals("prompt version v3 fixed citation support", reviewed.getReviewNote());
        verify(badCaseMapper).updateById(badCase);
    }
}
