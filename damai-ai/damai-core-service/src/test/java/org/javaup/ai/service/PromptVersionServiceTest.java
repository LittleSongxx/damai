package org.javaup.ai.service;

import org.javaup.ai.entity.AiPromptReleaseRecord;
import org.javaup.ai.entity.AiPromptVersion;
import org.javaup.ai.assistant.mcp.McpGovernanceProperties;
import org.javaup.ai.assistant.skill.ops.OpsEvidenceProvider;
import org.javaup.ai.assistant.skill.ops.OpsProviderRegistry;
import org.javaup.ai.assistant.skill.ops.OpsRcaRequest;
import org.javaup.ai.config.AiQualityGateProperties;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import org.javaup.ai.mapper.AiPromptReleaseRecordMapper;
import org.javaup.ai.mapper.AiPromptVersionMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import com.alibaba.fastjson2.JSON;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptVersionServiceTest {

    private final AiPromptVersionMapper versionMapper = mock(AiPromptVersionMapper.class);
    private final AiPromptReleaseRecordMapper releaseRecordMapper = mock(AiPromptReleaseRecordMapper.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final PromptVersionService service = new PromptVersionService(versionMapper, releaseRecordMapper, redisTemplate);

    @Test
    void createShouldCreateDraftThatDoesNotOverrideResolvedStableTemplate() {
        AiPromptVersion stable = version(1, "stable-template", true, "STABLE", 100);
        when(versionMapper.selectList(any())).thenReturn(List.of(stable), List.of(stable));
        when(versionMapper.insert(any(AiPromptVersion.class))).thenAnswer(invocation -> {
            AiPromptVersion inserted = invocation.getArgument(0);
            inserted.setId(2L);
            return 1;
        });

        AiPromptVersion draft = service.create("knowledge.answer", "draft-template", "new prompt", 7L);

        assertFalse(draft.getActive());
        assertEquals("DRAFT", draft.getRolloutStatus());
        assertEquals(0, draft.getTrafficPercent());
        assertEquals("stable-template", service.resolve("knowledge.answer", "default"));
    }

    @Test
    void publishGradualShouldKeepStableVersionActiveAndRecordRelease() {
        List<AiPromptVersion> versions = new ArrayList<>();
        versions.add(version(2, "candidate", false, "DRAFT", 0));
        versions.add(version(1, "stable", true, "STABLE", 100));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(versionMapper.selectById(2L)).thenAnswer(invocation -> versions.get(0));
        when(versionMapper.updateById(any(AiPromptVersion.class))).thenReturn(1);
        when(releaseRecordMapper.insert(any(AiPromptReleaseRecord.class))).thenAnswer(invocation -> {
            AiPromptReleaseRecord record = invocation.getArgument(0);
            assertEquals("PUBLISH", record.getActionType());
            assertEquals(1, record.getFromVersion());
            assertEquals(2, record.getToVersion());
            assertEquals("GRADUAL", record.getRolloutStatus());
            assertEquals(25, record.getTrafficPercent());
            assertEquals("rag-baseline-1", record.getBaselineEvalRunId());
            assertEquals("{\"status\":\"READY\"}", record.getReleaseEvidenceJson());
            assertNotNull(record.getReleaseId());
            return 1;
        });

        AiPromptVersion published = service.publish("knowledge.answer", 2, "GRADUAL", 25,
                "rag-baseline-1", "ship better citation prompt", "{\"status\":\"READY\"}", 9L);

        assertTrue(versions.get(0).getActive());
        assertEquals("GRADUAL", versions.get(0).getRolloutStatus());
        assertEquals(25, versions.get(0).getTrafficPercent());
        assertTrue(versions.get(1).getActive());
        assertEquals("STABLE", versions.get(1).getRolloutStatus());
        assertEquals(75, versions.get(1).getTrafficPercent());
        assertEquals("rag-baseline-1", published.getBaselineEvalRunId());
    }

    @Test
    void rollbackShouldPromoteTargetStableAndPersistReason() {
        List<AiPromptVersion> versions = new ArrayList<>();
        versions.add(version(3, "bad", true, "GRADUAL", 30));
        versions.add(version(2, "stable", true, "STABLE", 70));
        versions.add(version(1, "old", false, "SUPERSEDED", 0));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(versionMapper.selectById(1L)).thenAnswer(invocation -> versions.get(2));
        when(versionMapper.updateById(any(AiPromptVersion.class))).thenReturn(1);
        when(releaseRecordMapper.insert(any(AiPromptReleaseRecord.class))).thenAnswer(invocation -> {
            AiPromptReleaseRecord record = invocation.getArgument(0);
            assertEquals("ROLLBACK", record.getActionType());
            assertEquals(3, record.getFromVersion());
            assertEquals(1, record.getToVersion());
            assertEquals("judge regression", record.getRollbackReason());
            return 1;
        });

        AiPromptVersion rollback = service.rollback("knowledge.answer", 1, "judge regression", 9L);

        assertTrue(rollback.getActive());
        assertEquals("STABLE", rollback.getRolloutStatus());
        assertEquals("3", rollback.getRollbackFromVersion());
        assertEquals("judge regression", rollback.getRollbackReason());
        assertFalse(versions.get(0).getActive());
        assertEquals("ROLLED_BACK", versions.get(0).getRolloutStatus());
    }

    @Test
    void releasePlanShouldBindQualityGateBaselineAndRollbackEvidence() {
        AiRagEvalRunMapper ragMapper = mock(AiRagEvalRunMapper.class);
        AiNl2SqlEvalRunMapper sqlMapper = mock(AiNl2SqlEvalRunMapper.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(metricsService.qualitySnapshot()).thenReturn(passingCustomerServiceSnapshot());
        AiQualityGateService qualityGateService = new AiQualityGateService(
                ragMapper, sqlMapper, new McpGovernanceProperties(), opsRegistry(), metricsService, new AiQualityGateProperties());
        PromptReleasePlanService releasePlanService = new PromptReleasePlanService(service, qualityGateService);

        List<AiPromptVersion> versions = new ArrayList<>();
        versions.add(version(2, "candidate", false, "DRAFT", 0));
        versions.add(version(1, "stable", true, "STABLE", 100));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(ragMapper.selectOne(any())).thenReturn(passingRagRun());
        when(sqlMapper.selectOne(any())).thenReturn(passingSqlRun());

        Map<String, Object> plan = releasePlanService.buildPlan(
                "knowledge.answer", 2, "GRADUAL", 25, "rag-baseline-1");

        assertEquals("READY", plan.get("status"));
        assertEquals(true, plan.get("publishAllowed"));
        assertEquals(25, plan.get("recommendedTrafficPercent"));
        assertEquals(1, plan.get("rollbackTargetVersion"));
        assertEquals("rag-baseline-1", ((Map<?, ?>) plan.get("evidence")).get("baselineEvalRunId"));
        assertEquals("PASS", ((Map<?, ?>) plan.get("evidence")).get("qualityGateStatus"));
    }

    @Test
    void requirePublishablePlanShouldReturnServerGeneratedEvidencePlan() {
        AiRagEvalRunMapper ragMapper = mock(AiRagEvalRunMapper.class);
        AiNl2SqlEvalRunMapper sqlMapper = mock(AiNl2SqlEvalRunMapper.class);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(metricsService.qualitySnapshot()).thenReturn(passingCustomerServiceSnapshot());
        AiQualityGateService qualityGateService = new AiQualityGateService(
                ragMapper, sqlMapper, new McpGovernanceProperties(), opsRegistry(), metricsService, new AiQualityGateProperties());
        PromptReleasePlanService releasePlanService = new PromptReleasePlanService(service, qualityGateService);

        List<AiPromptVersion> versions = new ArrayList<>();
        versions.add(version(2, "candidate", false, "DRAFT", 0));
        versions.add(version(1, "stable", true, "STABLE", 100));
        when(versionMapper.selectList(any())).thenReturn(versions);
        when(ragMapper.selectOne(any())).thenReturn(passingRagRun());
        when(sqlMapper.selectOne(any())).thenReturn(passingSqlRun());

        Map<String, Object> plan = releasePlanService.requirePublishablePlan(
                "knowledge.answer", 2, "GRADUAL", 25, "rag-baseline-1");
        String evidenceJson = releasePlanService.releaseEvidenceJson(plan);

        assertEquals("READY", plan.get("status"));
        assertEquals(true, plan.get("publishAllowed"));
        assertTrue(evidenceJson.contains("\"qualityGateStatus\":\"PASS\""));
        assertTrue(evidenceJson.contains("\"promptKey\":\"knowledge.answer\""));
    }

    @Test
    void releasePlanShouldBlockMissingBaselineAndFailedGate() {
        AiRagEvalRunMapper ragMapper = mock(AiRagEvalRunMapper.class);
        AiNl2SqlEvalRunMapper sqlMapper = mock(AiNl2SqlEvalRunMapper.class);
        McpGovernanceProperties mcp = new McpGovernanceProperties();
        mcp.setExposeNl2Sql(true);
        CustomerServiceMetricsService metricsService = mock(CustomerServiceMetricsService.class);
        when(metricsService.qualitySnapshot()).thenReturn(Map.of("totalEvents", 0));
        AiQualityGateService qualityGateService = new AiQualityGateService(
                ragMapper, sqlMapper, mcp, opsRegistry(), metricsService, new AiQualityGateProperties());
        PromptReleasePlanService releasePlanService = new PromptReleasePlanService(service, qualityGateService);

        when(versionMapper.selectList(any())).thenReturn(List.of(version(2, "candidate", false, "DRAFT", 0)));
        when(ragMapper.selectOne(any())).thenReturn(null);
        when(sqlMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> plan = releasePlanService.buildPlan(
                "knowledge.answer", 2, "GRADUAL", 50, "");

        assertEquals("BLOCKED", plan.get("status"));
        assertEquals(false, plan.get("publishAllowed"));
        assertEquals(0, plan.get("recommendedTrafficPercent"));
        assertTrue(String.valueOf(plan.get("blockers")).contains("quality gate is blocking release"));
        assertTrue(String.valueOf(plan.get("blockers")).contains("baseline eval comparison is required"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                releasePlanService.requirePublishablePlan("knowledge.answer", 2, "GRADUAL", 50, ""));
        assertTrue(error.getMessage().contains("Prompt release blocked by quality gate"));
    }

    private AiPromptVersion version(int version, String template, boolean active, String rolloutStatus, int trafficPercent) {
        AiPromptVersion promptVersion = new AiPromptVersion();
        promptVersion.setId((long) version);
        promptVersion.setPromptKey("knowledge.answer");
        promptVersion.setVersion(version);
        promptVersion.setTemplate(template);
        promptVersion.setActive(active);
        promptVersion.setRolloutStatus(rolloutStatus);
        promptVersion.setTrafficPercent(trafficPercent);
        promptVersion.setStatus(1);
        return promptVersion;
    }

    private AiRagEvalRun passingRagRun() {
        AiRagEvalRun ragRun = new AiRagEvalRun();
        ragRun.setEvalRunId("rag-1");
        ragRun.setRunStatus("COMPLETED");
        ragRun.setCompletedCases(50);
        ragRun.setAvgRecall(0.9);
        ragRun.setAvgFaithfulness(0.95);
        ragRun.setAvgAnswerCorrectness(0.85);
        ragRun.setBaselineRunId("rag-baseline-1");
        ragRun.setReportJson(JSON.toJSONString(Map.of("closurePlan", Map.of(
                "baselineReady", true,
                "releaseBlocked", false,
                "baselineRunId", "rag-baseline-1"))));
        ragRun.setQualityGateJson(JSON.toJSONString(Map.of("status", "PASS")));
        return ragRun;
    }

    private Map<String, Object> passingCustomerServiceSnapshot() {
        return Map.of(
                "totalEvents", 20,
                "quickAnswerHitRate", 0.6,
                "cacheHitRate", 0.5,
                "workItemRate", 0.1,
                "negativeSentimentRate", 0.05,
                "satisfactionRate", 0.9,
                "avgFirstResponseLatencyMs", 120D);
    }

    private AiNl2SqlEvalRun passingSqlRun() {
        AiNl2SqlEvalRun sqlRun = new AiNl2SqlEvalRun();
        sqlRun.setEvalRunId("sql-1");
        sqlRun.setRunStatus("COMPLETED");
        sqlRun.setCompletedCases(50);
        sqlRun.setSqlValidityRate(0.95);
        sqlRun.setExecutionAccuracy(0.82);
        sqlRun.setSchemaLinkRecall(0.9);
        sqlRun.setSchemaLinkPrecision(0.9);
        sqlRun.setUnsafeRejectionRate(1.0);
        sqlRun.setLowConfidenceClarificationRate(0.2);
        return sqlRun;
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
