package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Nl2SqlOrchestratorPolicyTest {

    @Test
    void shouldConvertLowConfidenceSqlToClarification() {
        Nl2SqlProperties properties = new Nl2SqlProperties();
        properties.setMinSqlConfidence(0.7D);
        Nl2SqlOrchestrator orchestrator = new Nl2SqlOrchestrator(
                null, properties, null, null, null, null, null, null, null, null, null, null);
        Nl2SqlGenerationResult generation = new Nl2SqlGenerationResult();
        generation.setNeedSql(true);
        generation.setSql("select stat_date from v_order_daily_summary limit 10");
        generation.setConfidence(0.42D);
        generation.setExplanation("时间范围不明确");
        generation.setTables(List.of("v_order_daily_summary"));
        generation.setAssumptions(List.of("默认查询今天"));

        Map<String, Object> clarification = orchestrator.clarificationIfLowConfidence(generation);

        assertEquals("NEED_CLARIFICATION", clarification.get("status"));
        assertEquals("时间范围不明确", clarification.get("message"));
        Map<?, ?> safetyReport = (Map<?, ?>) clarification.get("safetyReport");
        assertEquals(Boolean.TRUE, safetyReport.get("lowConfidenceBlocked"));
        assertEquals(Boolean.TRUE, safetyReport.get("unsafeExecutionRejected"));
        assertEquals(0.42D, safetyReport.get("confidence"));
        assertTrue(String.valueOf(clarification.get("evidence")).contains("v_order_daily_summary"));
        assertEquals("", clarification.get("sql"));
        assertTrue(clarification.containsKey("executionPlan"));
        assertTrue(clarification.containsKey("resultPreview"));
        assertTrue(clarification.containsKey("maskedColumns"));
        assertTrue(clarification.containsKey("repairTrace"));
    }

    @Test
    void shouldAllowSqlWhenConfidenceMeetsThreshold() {
        Nl2SqlProperties properties = new Nl2SqlProperties();
        properties.setMinSqlConfidence(0.5D);
        Nl2SqlOrchestrator orchestrator = new Nl2SqlOrchestrator(
                null, properties, null, null, null, null, null, null, null, null, null, null);
        Nl2SqlGenerationResult generation = new Nl2SqlGenerationResult();
        generation.setNeedSql(true);
        generation.setConfidence(0.8D);

        assertNull(orchestrator.clarificationIfLowConfidence(generation));
    }

    @Test
    void shouldFinalizeFailureWithStructuredSafetyContract() {
        Nl2SqlProperties properties = new Nl2SqlProperties();
        Nl2SqlOrchestrator orchestrator = new Nl2SqlOrchestrator(
                null, properties, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", "FAILED");
        response.put("message", "字段不在 NL2SQL 白名单内: mobile");
        response.put("safetyReport", Map.of(
                "unsafeExecutionRejected", true,
                "rejectionStage", "SAFETY_OR_COST_GUARD"));

        Map<String, Object> finalized = orchestrator.finalizeResponse(response);

        assertEquals("FAILED", finalized.get("status"));
        assertEquals("", finalized.get("sql"));
        Map<?, ?> safetyReport = (Map<?, ?>) finalized.get("safetyReport");
        assertEquals(Boolean.TRUE, safetyReport.get("unsafeExecutionRejected"));
        assertEquals("SAFETY_OR_COST_GUARD", safetyReport.get("rejectionStage"));
        assertTrue(finalized.containsKey("evidence"));
        assertTrue(finalized.containsKey("executionPlan"));
        assertTrue(finalized.containsKey("resultPreview"));
        assertTrue(finalized.containsKey("maskedColumns"));
        assertTrue(finalized.containsKey("repairTrace"));
    }

    @Test
    void shouldExposeCostGuardInExecutionPlanAndSafetyReport() {
        Nl2SqlProperties properties = new Nl2SqlProperties();
        properties.setMaxRows(20);
        properties.setQueryTimeoutMs(3000);
        properties.getCostGuard().setExplainTimeoutMs(1500);
        properties.getCostGuard().setMaxEstimatedRows(5000);
        properties.getCostGuard().setMaxQueryCost(100D);
        Nl2SqlOrchestrator orchestrator = new Nl2SqlOrchestrator(
                null, properties, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", "COMPLETED");
        response.put("sql", "select stat_date from v_order_daily_summary limit 20");
        response.put("costGuard", Map.of(
                "status", "PASSED",
                "explainMode", "JSON",
                "estimatedRows", 12L,
                "queryCost", 8.5D));
        response.put("execution", Nl2SqlExecutionResult.builder()
                .sql("select stat_date from v_order_daily_summary limit 20")
                .columns(List.of("stat_date"))
                .rows(List.of(Map.of("stat_date", "2026-06-30")))
                .rowCount(1)
                .truncated(false)
                .skipped(false)
                .costGuard(Map.of(
                        "status", "PASSED",
                        "explainMode", "JSON",
                        "estimatedRows", 12L,
                        "queryCost", 8.5D))
                .durationMs(42)
                .build());

        Map<String, Object> finalized = orchestrator.finalizeResponse(response);

        Map<?, ?> executionPlan = (Map<?, ?>) finalized.get("executionPlan");
        assertEquals("EXPLAIN_LIMIT_AND_TIMEOUT", executionPlan.get("costGuard"));
        assertEquals(12L, executionPlan.get("estimatedRows"));
        assertEquals(8.5D, executionPlan.get("queryCost"));
        assertEquals(1, executionPlan.get("rowCount"));
        assertEquals(42L, executionPlan.get("durationMs"));
        Map<?, ?> costGuardReport = (Map<?, ?>) executionPlan.get("costGuardReport");
        assertEquals("PASSED", costGuardReport.get("status"));
        Map<?, ?> safetyReport = (Map<?, ?>) finalized.get("safetyReport");
        assertEquals("PASSED", safetyReport.get("costGuardStatus"));
        assertEquals("JSON", safetyReport.get("costGuardExplainMode"));
        assertEquals(12L, safetyReport.get("estimatedRows"));
        assertEquals(8.5D, safetyReport.get("queryCost"));
        assertEquals(5000L, safetyReport.get("maxEstimatedRows"));
        assertEquals(100D, safetyReport.get("maxQueryCost"));
        assertEquals(1, safetyReport.get("rowCount"));
        assertEquals(false, safetyReport.get("truncated"));
        assertEquals(42L, safetyReport.get("durationMs"));
    }
}
