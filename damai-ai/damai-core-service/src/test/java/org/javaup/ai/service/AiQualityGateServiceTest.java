package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.assistant.mcp.McpGovernanceProperties;
import org.javaup.ai.assistant.skill.ops.OpsEvidenceProvider;
import org.javaup.ai.assistant.skill.ops.OpsProviderRegistry;
import org.javaup.ai.assistant.skill.ops.OpsRcaRequest;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiQualityGateServiceTest {

    @Test
    void shouldReturnPassWhenLatestEvalRunsAndMcpGovernancePass() {
        AiRagEvalRunMapper ragMapper = mock(AiRagEvalRunMapper.class);
        AiNl2SqlEvalRunMapper nl2SqlMapper = mock(AiNl2SqlEvalRunMapper.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        McpGovernanceProperties mcp = new McpGovernanceProperties();
        when(metricsService.qualitySnapshot()).thenReturn(Map.of(
                "totalEvents", 20,
                "quickAnswerHitRate", 0.6,
                "cacheHitRate", 0.5,
                "workItemRate", 0.1,
                "negativeSentimentRate", 0.05,
                "satisfactionRate", 0.9,
                "avgFirstResponseLatencyMs", 120D));
        AiQualityGateService service = new AiQualityGateService(
                ragMapper, nl2SqlMapper, mcp, opsRegistry(), metricsService);

        AiRagEvalRun ragRun = new AiRagEvalRun();
        ragRun.setEvalRunId("rag-1");
        ragRun.setRunStatus("COMPLETED");
        ragRun.setCompletedCases(50);
        ragRun.setAvgRecall(0.9);
        ragRun.setAvgFaithfulness(0.95);
        ragRun.setAvgAnswerCorrectness(0.85);
        ragRun.setBaselineRunId("rag-base");
        ragRun.setReportJson(JSON.toJSONString(Map.of("closurePlan", Map.of(
                "baselineReady", true,
                "releaseBlocked", false,
                "candidateEvalCaseIds", java.util.List.of("case-1"),
                "nextActions", java.util.List.of("approve prompt/config release with rollback record")))));
        ragRun.setQualityGateJson(JSON.toJSONString(Map.of("status", "PASS")));

        AiNl2SqlEvalRun sqlRun = new AiNl2SqlEvalRun();
        sqlRun.setEvalRunId("sql-1");
        sqlRun.setRunStatus("COMPLETED");
        sqlRun.setCompletedCases(50);
        sqlRun.setSqlValidityRate(0.95);
        sqlRun.setExecutionAccuracy(0.82);
        sqlRun.setExactMatchRate(0.7);
        sqlRun.setSchemaLinkRecall(0.9);
        sqlRun.setSchemaLinkPrecision(0.9);
        sqlRun.setUnsafeRejectionRate(1.0);
        sqlRun.setLowConfidenceClarificationRate(0.2);

        when(ragMapper.selectOne(any())).thenReturn(ragRun);
        when(nl2SqlMapper.selectOne(any())).thenReturn(sqlRun);

        Map<String, Object> gate = service.latestGate();

        assertEquals("PASS", gate.get("status"));
        assertEquals("rag-1", gate.get("latestRagRunId"));
        assertEquals("sql-1", gate.get("latestNl2SqlRunId"));
        assertEquals("READY", ((Map<?, ?>) gate.get("releaseReadiness")).get("status"));
        assertEquals(true, ((Map<?, ?>) gate.get("coverageSummary")).containsKey("domains"));
        assertEquals(true, String.valueOf(gate.get("capabilityEvidence")).contains("assistant-eval-control-plane"));
        assertEquals(true, String.valueOf(gate.get("capabilityEvidence")).contains("rag-reindex-jobs"));
        assertEquals(true, String.valueOf(gate.get("trendSummary")).contains("ragBaselineRunId"));
        Map<?, ?> ragClosure = (Map<?, ?>) gate.get("ragClosure");
        assertEquals("READY", ragClosure.get("status"));
        assertEquals(true, ragClosure.get("baselineReady"));
        assertEquals(false, ragClosure.get("releaseBlocked"));
        assertEquals(true, String.valueOf(ragClosure.get("workflow")).contains("convert to eval"));
        Map<?, ?> nl2SqlContract = (Map<?, ?>) gate.get("nl2SqlContract");
        assertEquals("READY", nl2SqlContract.get("status"));
        assertEquals(true, nl2SqlContract.get("contractReady"));
        assertEquals(true, String.valueOf(nl2SqlContract.get("responseFields")).contains("safetyReport"));
        assertEquals(true, nl2SqlContract.get("costGuardReady"));
        assertEquals("EXPLAIN_LIMIT_AND_TIMEOUT", nl2SqlContract.get("costGuardPolicy"));
        assertEquals(true, String.valueOf(nl2SqlContract.get("executionPlanFields")).contains("queryCost"));
    }

    @Test
    void shouldFailWhenMcpExposesNl2SqlByDefaultOrSqlEvalFails() {
        AiRagEvalRunMapper ragMapper = mock(AiRagEvalRunMapper.class);
        AiNl2SqlEvalRunMapper nl2SqlMapper = mock(AiNl2SqlEvalRunMapper.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        McpGovernanceProperties mcp = new McpGovernanceProperties();
        mcp.setExposeNl2Sql(true);
        when(metricsService.qualitySnapshot()).thenReturn(Map.of("totalEvents", 0));
        AiQualityGateService service = new AiQualityGateService(
                ragMapper, nl2SqlMapper, mcp, opsRegistry(), metricsService);

        AiNl2SqlEvalRun sqlRun = new AiNl2SqlEvalRun();
        sqlRun.setEvalRunId("sql-bad");
        sqlRun.setRunStatus("COMPLETED");
        sqlRun.setSqlValidityRate(0.8);
        sqlRun.setExecutionAccuracy(0.5);

        when(ragMapper.selectOne(any())).thenReturn(null);
        when(nl2SqlMapper.selectOne(any())).thenReturn(sqlRun);

        Map<String, Object> gate = service.latestGate();

        assertEquals("FAIL", gate.get("status"));
        assertEquals("BLOCKED", ((Map<?, ?>) gate.get("releaseReadiness")).get("status"));
        assertEquals(true, String.valueOf(gate.get("failureSamples")).contains("NL2SQL_EVAL"));
        assertEquals(true, String.valueOf(gate.get("failureSamples")).contains("MCP_GOVERNANCE"));
        assertEquals("MISSING_EVAL", ((Map<?, ?>) gate.get("ragClosure")).get("status"));
        assertEquals("ACTION_REQUIRED", ((Map<?, ?>) gate.get("nl2SqlContract")).get("status"));
    }

    private OpsProviderRegistry opsRegistry() {
        OpsProviderRegistry registry = new OpsProviderRegistry();
        registry.setProviders(List.of(
                provider("logs"), provider("metrics"), provider("traces"), provider("alerts"), provider("businessEvents")));
        return registry;
    }

    private OpsEvidenceProvider provider(String signalType) {
        return new OpsEvidenceProvider() {
            @Override
            public String name() {
                return signalType + "-test";
            }

            @Override
            public String signalType() {
                return signalType;
            }

            @Override
            public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
                return Map.of("items", List.of("ok"));
            }
        };
    }
}
