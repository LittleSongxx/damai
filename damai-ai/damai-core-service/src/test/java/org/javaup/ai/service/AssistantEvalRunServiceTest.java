package org.javaup.ai.service;

import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.vo.AssistantEvalRunRequest;
import org.javaup.ai.vo.AssistantEvalRunVo;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEvalRunServiceTest {

    private final RagEvalService ragEvalService = mock(RagEvalService.class);
    private final Nl2SqlEvalService nl2SqlEvalService = mock(Nl2SqlEvalService.class);
    private final AiQualityGateService qualityGateService = mock(AiQualityGateService.class);
    private final AssistantEvalRunService service = new AssistantEvalRunService(
            ragEvalService, nl2SqlEvalService, qualityGateService);

    @Test
    void shouldStartRagEvalSuiteThroughUnifiedControlPlane() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("rag-eval-1");
        run.setRunStatus("RUNNING");
        run.setTotalCases(50);
        when(ragEvalService.startEvaluation(any(RagEvalRunRequest.class))).thenReturn(run);

        AssistantEvalRunRequest request = new AssistantEvalRunRequest();
        request.setDatasetId("default-golden");
        request.setDatasetVersion("v2");
        request.setCategory("refund");
        request.setDifficulty("hard");
        request.setCaseIds(List.of("case-1"));
        request.setLimit(10);
        request.setBaselineRunId("rag-base");
        request.setPromptVersion("p3");
        request.setModelVersion("deepseek-v3");

        AssistantEvalRunVo result = service.runSuite("rag", request);

        assertEquals("RAG", result.getSuite());
        assertEquals("RUNNING", result.getStatus());
        assertEquals("rag-eval-1", result.getEvalRunId());
        assertEquals(50, result.getTotalCases());
        assertEquals("RAG_EVAL_RUN", result.getResultType());
        assertEquals("RUNNING", result.getQualityGate().get("status"));

        ArgumentCaptor<RagEvalRunRequest> captor = ArgumentCaptor.forClass(RagEvalRunRequest.class);
        verify(ragEvalService).startEvaluation(captor.capture());
        assertEquals("default-golden", captor.getValue().getDatasetId());
        assertEquals("v2", captor.getValue().getDatasetVersion());
        assertEquals("refund", captor.getValue().getCategory());
        assertEquals("hard", captor.getValue().getDifficulty());
        assertEquals(List.of("case-1"), captor.getValue().getCaseIds());
        assertEquals(10, captor.getValue().getLimit());
        assertEquals("rag-base", captor.getValue().getBaselineRunId());
        assertEquals("p3", captor.getValue().getPromptVersion());
        assertEquals("deepseek-v3", captor.getValue().getModelVersion());
    }

    @Test
    void shouldStartNl2SqlEvalSuiteThroughUnifiedControlPlane() {
        AiNl2SqlEvalRun run = new AiNl2SqlEvalRun();
        run.setEvalRunId("sql-eval-1");
        run.setRunStatus("RUNNING");
        run.setTotalCases(20);
        when(nl2SqlEvalService.startEvaluation(eq("sales"), eq("medium"), eq(List.of("sql-case-1"))))
                .thenReturn(run);

        AssistantEvalRunRequest request = new AssistantEvalRunRequest();
        request.setCategory("sales");
        request.setDifficulty("medium");
        request.setCaseIds(List.of("sql-case-1"));

        AssistantEvalRunVo result = service.runSuite("nl2sql", request);

        assertEquals("NL2SQL", result.getSuite());
        assertEquals("RUNNING", result.getStatus());
        assertEquals("sql-eval-1", result.getEvalRunId());
        assertEquals(20, result.getTotalCases());
        assertEquals("NL2SQL_EVAL_RUN", result.getResultType());
        assertEquals(true, result.getNextActions().stream().anyMatch(action -> action.contains("unsafe rejection")));
    }

    @Test
    void shouldExposeQualityGateSnapshotForRedTeamSuite() {
        when(qualityGateService.latestGate()).thenReturn(Map.of(
                "status", "PASS",
                "latestRagRunId", "rag-1",
                "latestNl2SqlRunId", "sql-1"));

        AssistantEvalRunVo result = service.runSuite("red-team", null);

        assertEquals("RED_TEAM", result.getSuite());
        assertEquals("PASS", result.getStatus());
        assertEquals("QUALITY_GATE_RED_TEAM", result.getResultType());
        assertEquals("rag-1", result.getQualityGate().get("latestRagRunId"));
    }

    @Test
    void shouldReturnUnifiedRagEvalStatusWithMetricsAndGate() {
        AiRagEvalRun run = new AiRagEvalRun();
        run.setEvalRunId("rag-eval-status");
        run.setRunStatus("COMPLETED");
        run.setTotalCases(10);
        run.setCompletedCases(10);
        run.setAvgRecall(0.86);
        run.setAvgFaithfulness(0.91);
        run.setAvgAnswerCorrectness(0.88);
        run.setAvgCtxPrecision(0.8);
        run.setAvgCtxRecall(0.82);
        when(ragEvalService.getRunStatus("rag-eval-status")).thenReturn(run);
        when(ragEvalService.buildQualityGate(run)).thenReturn(Map.of("status", "PASS"));

        AssistantEvalRunVo result = service.getSuiteRun("rag", "rag-eval-status");

        assertEquals("RAG", result.getSuite());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1.0, result.getProgress(), 0.0001);
        assertEquals(0.86, (Double) result.getMetrics().get("avgRecall"), 0.0001);
        assertEquals("PASS", result.getQualityGate().get("status"));
        assertEquals(true, result.getNextActions().stream().anyMatch(action -> action.contains("bad cases")));
    }

    @Test
    void shouldReturnUnifiedNl2SqlEvalStatusWithSafetyMetrics() {
        AiNl2SqlEvalRun run = new AiNl2SqlEvalRun();
        run.setEvalRunId("sql-eval-status");
        run.setRunStatus("COMPLETED");
        run.setTotalCases(8);
        run.setCompletedCases(6);
        run.setSqlValidityRate(0.92);
        run.setExecutionAccuracy(0.8);
        run.setSchemaLinkRecall(0.85);
        run.setSchemaLinkPrecision(0.84);
        run.setUnsafeRejectionRate(1.0);
        run.setResultSetEquivalenceRate(0.78);
        run.setRepairSuccessRate(0.5);
        run.setAvgLatencyMs(1200.0);
        when(nl2SqlEvalService.getRunStatus("sql-eval-status")).thenReturn(run);

        AssistantEvalRunVo result = service.getSuiteRun("nl2sql", "sql-eval-status");

        assertEquals("NL2SQL", result.getSuite());
        assertEquals(0.75, result.getProgress(), 0.0001);
        assertEquals(0.8, (Double) result.getMetrics().get("executionAccuracy"), 0.0001);
        assertEquals(1.0, (Double) result.getMetrics().get("unsafeRejectionRate"), 0.0001);
        assertEquals("PASS", result.getQualityGate().get("status"));
    }

    @Test
    void shouldReturnNullForMissingEvalRunStatus() {
        when(ragEvalService.getRunStatus("missing")).thenReturn(null);

        AssistantEvalRunVo result = service.getSuiteRun("rag", "missing");

        assertEquals(null, result);
    }

    @Test
    void shouldRejectUnsupportedSuite() {
        assertThrows(IllegalArgumentException.class, () -> service.runSuite("unknown", null));
    }
}
