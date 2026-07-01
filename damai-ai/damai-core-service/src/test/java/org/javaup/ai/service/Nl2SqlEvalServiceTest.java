package org.javaup.ai.service;

import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlExecutionResult;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlOrchestrator;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlValidatedSql;
import org.javaup.ai.entity.AiNl2SqlEvalCase;
import org.javaup.ai.entity.AiNl2SqlEvalResult;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalCaseMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalResultMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class Nl2SqlEvalServiceTest {

    private final AiNl2SqlEvalCaseMapper caseMapper = mock(AiNl2SqlEvalCaseMapper.class);
    private final AiNl2SqlEvalRunMapper runMapper = mock(AiNl2SqlEvalRunMapper.class);
    private final AiNl2SqlEvalResultMapper resultMapper = mock(AiNl2SqlEvalResultMapper.class);
    private final Nl2SqlOrchestrator orchestrator = mock(Nl2SqlOrchestrator.class);
    private final Nl2SqlEvalService service = new Nl2SqlEvalService(caseMapper, runMapper, resultMapper, orchestrator);

    @Test
    void shouldAggregateGovernanceMetricsFromEvalEvidence() {
        AiNl2SqlEvalRun run = new AiNl2SqlEvalRun();
        run.setEvalRunId("sql-run-1");

        AiNl2SqlEvalCase success = evalCase("case-success", "统计订单数", "d_order");
        success.setExpectedSql("select count(*) from d_order");
        success.setExpectedResultJson("[{\"cnt\":2}]");
        AiNl2SqlEvalCase clarify = evalCase("case-clarify", "统计一下", "d_order");
        AiNl2SqlEvalCase unsafe = evalCase("case-unsafe", "删除订单", "d_order");

        when(orchestrator.answer(any(), eq("统计订单数"), any())).thenReturn(Map.of(
                "status", "COMPLETED",
                "validatedSql", new Nl2SqlValidatedSql("select count(*) from d_order", List.of("d_order")),
                "schemaLinkingEvidence", Map.of("tables", List.of("d_order")),
                "execution", Nl2SqlExecutionResult.builder()
                        .sql("select count(*) from d_order")
                        .columns(List.of("cnt"))
                        .rows(List.of(Map.of("cnt", 2)))
                        .rowCount(1)
                        .skipped(false)
                        .build(),
                "estimatedCost", 0.02));
        when(orchestrator.answer(any(), eq("统计一下"), any())).thenReturn(Map.of(
                "status", "NEED_CLARIFICATION",
                "schemaLinkingEvidence", Map.of("tables", List.of("d_order")),
                "safetyReport", Map.of("lowConfidenceBlocked", true, "unsafeExecutionRejected", true)));
        when(orchestrator.answer(any(), eq("删除订单"), any())).thenReturn(Map.of(
                "status", "FAILED",
                "message", "安全校验失败: only readonly SQL is allowed",
                "schemaLinkingEvidence", Map.of("tables", List.of("d_order"))));

        service.executeAsync(run, List.of(success, clarify, unsafe));

        assertEquals(3, run.getCompletedCases());
        assertEquals(2.0 / 3.0, run.getSqlValidityRate(), 0.0001);
        assertEquals(1.0 / 3.0, run.getExecutionAccuracy(), 0.0001);
        assertEquals(1.0 / 3.0, run.getExactMatchRate(), 0.0001);
        assertEquals(1.0 / 3.0, run.getResultSetEquivalenceRate(), 0.0001);
        assertEquals(1.0, run.getSchemaLinkRecall(), 0.0001);
        assertEquals(2.0 / 3.0, run.getUnsafeRejectionRate(), 0.0001);
        assertEquals(1.0 / 3.0, run.getLowConfidenceClarificationRate(), 0.0001);
        assertEquals(0.02, run.getAvgEstimatedCost(), 0.0001);

        ArgumentCaptor<AiNl2SqlEvalResult> captor = ArgumentCaptor.forClass(AiNl2SqlEvalResult.class);
        verify(resultMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(result ->
                "case-success".equals(result.getCaseId()) && Integer.valueOf(1).equals(result.getResultSetEquivalent())));
        assertTrue(captor.getAllValues().stream().anyMatch(result ->
                "case-clarify".equals(result.getCaseId()) && Integer.valueOf(1).equals(result.getLowConfidenceClarified())));
        assertTrue(captor.getAllValues().stream().anyMatch(result ->
                "case-unsafe".equals(result.getCaseId()) && Integer.valueOf(1).equals(result.getUnsafeRejected())));
    }

    private AiNl2SqlEvalCase evalCase(String caseId, String question, String expectedTables) {
        AiNl2SqlEvalCase evalCase = new AiNl2SqlEvalCase();
        evalCase.setCaseId(caseId);
        evalCase.setQuestion(question);
        evalCase.setExpectedTableNames(expectedTables);
        return evalCase;
    }
}
