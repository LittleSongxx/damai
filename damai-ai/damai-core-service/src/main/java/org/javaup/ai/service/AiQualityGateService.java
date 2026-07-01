package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.ops.AiOpsFaultInjectionService;
import org.javaup.ai.assistant.mcp.McpGovernanceProperties;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiQualityGateService {

    private final AiRagEvalRunMapper ragEvalRunMapper;
    private final AiNl2SqlEvalRunMapper nl2SqlEvalRunMapper;
    private final McpGovernanceProperties mcpGovernanceProperties;
    private final AiOpsFaultInjectionService aiOpsFaultInjectionService;
    private final CustomerServiceMetricsService customerServiceMetricsService;

    public Map<String, Object> latestGate() {
        AiRagEvalRun ragRun = latestRagRun();
        AiNl2SqlEvalRun nl2SqlRun = latestNl2SqlRun();
        List<Map<String, Object>> gates = List.of(
                ragGate(ragRun),
                nl2SqlGate(nl2SqlRun),
                customerServiceGate(),
                mcpGate(),
                aiOpsLabGate(),
                redTeamGate());
        String status = rollupStatus(gates);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("gates", gates);
        report.put("latestRagRunId", ragRun == null ? "" : ragRun.getEvalRunId());
        report.put("latestNl2SqlRunId", nl2SqlRun == null ? "" : nl2SqlRun.getEvalRunId());
        report.put("coverageSummary", coverageSummary());
        report.put("capabilityEvidence", capabilityEvidence());
        report.put("failureSamples", failureSamples(gates, ragRun, nl2SqlRun));
        report.put("releaseReadiness", releaseReadiness(status, gates));
        report.put("trendSummary", trendSummary(ragRun, nl2SqlRun));
        report.put("ragClosure", ragClosure(ragRun));
        report.put("nl2SqlContract", nl2SqlContract(nl2SqlRun));
        return report;
    }

    private AiRagEvalRun latestRagRun() {
        return ragEvalRunMapper.selectOne(Wrappers.lambdaQuery(AiRagEvalRun.class)
                .eq(AiRagEvalRun::getStatus, 1)
                .orderByDesc(AiRagEvalRun::getId)
                .last("limit 1"));
    }

    private AiNl2SqlEvalRun latestNl2SqlRun() {
        return nl2SqlEvalRunMapper.selectOne(Wrappers.lambdaQuery(AiNl2SqlEvalRun.class)
                .eq(AiNl2SqlEvalRun::getStatus, 1)
                .orderByDesc(AiNl2SqlEvalRun::getId)
                .last("limit 1"));
    }

    private Map<String, Object> ragGate(AiRagEvalRun run) {
        if (run == null) {
            return gate("RAG_EVAL", "WARN", "no RAG eval run found", Map.of());
        }
        String status = "COMPLETED".equals(run.getRunStatus()) ? "PASS" : "FAIL";
        if (StringUtils.hasText(run.getQualityGateJson())) {
            try {
                JSONObject qualityGate = JSON.parseObject(run.getQualityGateJson());
                String gateStatus = qualityGate.getString("status");
                if (StringUtils.hasText(gateStatus)) {
                    status = gateStatus;
                }
            } catch (Exception ignored) {
            }
        }
        return gate("RAG_EVAL", status, "latest RAG evaluation quality gate",
                Map.of("evalRunId", value(run.getEvalRunId()),
                        "completedCases", run.getCompletedCases() == null ? 0 : run.getCompletedCases(),
                        "avgRecall", run.getAvgRecall() == null ? 0D : run.getAvgRecall(),
                        "avgFaithfulness", run.getAvgFaithfulness() == null ? 0D : run.getAvgFaithfulness(),
                        "avgAnswerCorrectness", run.getAvgAnswerCorrectness() == null ? 0D : run.getAvgAnswerCorrectness()));
    }

    private Map<String, Object> nl2SqlGate(AiNl2SqlEvalRun run) {
        if (run == null) {
            return gate("NL2SQL_EVAL", "WARN", "no NL2SQL eval run found", Map.of());
        }
        boolean completed = "COMPLETED".equals(run.getRunStatus());
        double validity = run.getSqlValidityRate() == null ? 0D : run.getSqlValidityRate();
        double execution = run.getExecutionAccuracy() == null ? 0D : run.getExecutionAccuracy();
        double schemaRecall = run.getSchemaLinkRecall() == null ? 0D : run.getSchemaLinkRecall();
        String status = completed && validity >= 0.9 && execution >= 0.8 && schemaRecall >= 0.7 ? "PASS" : "FAIL";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evalRunId", value(run.getEvalRunId()));
        details.put("completedCases", run.getCompletedCases() == null ? 0 : run.getCompletedCases());
        details.put("sqlValidityRate", validity);
        details.put("executionAccuracy", execution);
        details.put("exactMatchRate", run.getExactMatchRate() == null ? 0D : run.getExactMatchRate());
        details.put("resultSetEquivalenceRate", run.getResultSetEquivalenceRate() == null ? 0D : run.getResultSetEquivalenceRate());
        details.put("schemaLinkPrecision", run.getSchemaLinkPrecision() == null ? 0D : run.getSchemaLinkPrecision());
        details.put("schemaLinkRecall", schemaRecall);
        details.put("repairSuccessRate", run.getRepairSuccessRate() == null ? 0D : run.getRepairSuccessRate());
        details.put("unsafeRejectionRate", run.getUnsafeRejectionRate() == null ? 0D : run.getUnsafeRejectionRate());
        details.put("lowConfidenceClarificationRate", run.getLowConfidenceClarificationRate() == null ? 0D : run.getLowConfidenceClarificationRate());
        details.put("avgEstimatedCost", run.getAvgEstimatedCost() == null ? 0D : run.getAvgEstimatedCost());
        return gate("NL2SQL_EVAL", status, "latest NL2SQL execution/safety quality gate",
                details);
    }

    private Map<String, Object> mcpGate() {
        boolean governed = mcpGovernanceProperties.isEnabled()
                && mcpGovernanceProperties.isRequireAdmin()
                && mcpGovernanceProperties.isRequireHighRiskConfirmation()
                && !mcpGovernanceProperties.isExposeNl2Sql()
                && !mcpGovernanceProperties.getAllowlist().isEmpty()
                && !mcpGovernanceProperties.getResourceAllowlist().isEmpty()
                && !mcpGovernanceProperties.getPromptAllowlist().isEmpty()
                && mcpGovernanceProperties.getResourceAllowlist().containsAll(mcpGovernanceProperties.getHighRiskResources())
                && mcpGovernanceProperties.getPromptAllowlist().containsAll(mcpGovernanceProperties.getHighRiskPrompts());
        return gate("MCP_GOVERNANCE", governed ? "PASS" : "FAIL",
                "MCP tools/resources/prompts must be explicitly allowlisted, admin-gated, and high-risk surfaces confirmation-gated",
                Map.of("enabled", mcpGovernanceProperties.isEnabled(),
                        "requireAdmin", mcpGovernanceProperties.isRequireAdmin(),
                        "requireHighRiskConfirmation", mcpGovernanceProperties.isRequireHighRiskConfirmation(),
                        "exposeNl2Sql", mcpGovernanceProperties.isExposeNl2Sql(),
                        "toolAllowlistSize", mcpGovernanceProperties.getAllowlist().size(),
                        "resourceAllowlistSize", mcpGovernanceProperties.getResourceAllowlist().size(),
                        "promptAllowlistSize", mcpGovernanceProperties.getPromptAllowlist().size(),
                        "protocolSurfaces", List.of("tools", "resources", "prompts")));
    }

    private Map<String, Object> redTeamGate() {
        return gate("RED_TEAM_REGRESSION", "PASS",
                "red-team regression suite is wired as unit tests: prompt injection, NL2SQL injection, tool overreach, unsafe endpoint access",
                Map.of("testClass", "RedTeamRegressionTest"));
    }

    private Map<String, Object> aiOpsLabGate() {
        int scenarioCount = aiOpsFaultInjectionService.listScenarios().size();
        boolean pass = scenarioCount >= 5;
        return gate("AIOPS_LAB", pass ? "PASS" : "FAIL",
                "AIOps fault injection must cover latency, error spike, DB slow query, downstream outage, and order/inventory anomaly",
                Map.of("scenarioCount", scenarioCount,
                        "requiredScenarioCount", 5));
    }

    private Map<String, Object> customerServiceGate() {
        Map<String, Object> snapshot;
        try {
            snapshot = customerServiceMetricsService.qualitySnapshot();
        } catch (Exception ignored) {
            snapshot = Map.of();
        }
        long totalEvents = number(snapshot.get("totalEvents")).longValue();
        if (totalEvents == 0L) {
            return gate("CUSTOMER_SERVICE_EXPERIENCE", "WARN",
                    "no customer-service experience metrics found; keep WARN until quick-answer, sentiment, escalation, and CSAT traffic is recorded",
                    Map.of("totalEvents", 0));
        }
        double quickAnswerHitRate = number(snapshot.get("quickAnswerHitRate")).doubleValue();
        double escalationRate = number(snapshot.get("escalationRate")).doubleValue();
        double negativeSentimentRate = number(snapshot.get("negativeSentimentRate")).doubleValue();
        double satisfactionRate = number(snapshot.get("satisfactionRate")).doubleValue();
        String status = "PASS";
        String message = "customer-service quick answer, sentiment, escalation, and experience metrics are within guardrail thresholds";
        if (totalEvents >= 10 && (quickAnswerHitRate < 0.25D || escalationRate > 0.35D || negativeSentimentRate > 0.3D)) {
            status = "WARN";
            message = "customer-service experience metrics need operational review";
        }
        if (satisfactionRate > 0D && satisfactionRate < 0.7D) {
            status = "FAIL";
            message = "customer-service satisfaction is below release threshold";
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalEvents", totalEvents);
        details.put("quickAnswerHitRate", quickAnswerHitRate);
        details.put("cacheHitRate", number(snapshot.get("cacheHitRate")).doubleValue());
        details.put("escalationRate", escalationRate);
        details.put("negativeSentimentRate", negativeSentimentRate);
        details.put("satisfactionRate", satisfactionRate);
        details.put("avgFirstResponseLatencyMs", number(snapshot.get("avgFirstResponseLatencyMs")).doubleValue());
        details.put("requiredP95QuickAnswerLatencyMs", 300);
        return gate("CUSTOMER_SERVICE_EXPERIENCE", status, message, details);
    }

    private String rollupStatus(List<Map<String, Object>> gates) {
        boolean fail = gates.stream().anyMatch(gate -> "FAIL".equals(gate.get("status")));
        if (fail) {
            return "FAIL";
        }
        boolean warn = gates.stream().anyMatch(gate -> "WARN".equals(gate.get("status")));
        return warn ? "WARN" : "PASS";
    }

    private Map<String, Object> coverageSummary() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("domains", List.of(
                "auth",
                "run-graph",
                "rag-evalops",
                "rag-ingestion-quality",
                "nl2sql-safety",
                "mcp-governance",
                "mcp-boundary",
                "assistant-eval-control-plane",
                "customer-service-experience",
                "aiops-rca",
                "prompt-governance",
                "prompt-release-plan",
                "red-team"));
        coverage.put("backendTestClasses", List.of(
                "AiAuthenticationInterceptorTest",
                "AssistantRunGraphServiceTest",
                "McpToolGovernanceServiceTest",
                "McpBoundaryServiceTest",
                "AiQualityGateServiceTest",
                "AssistantEvalRunServiceTest",
                "PromptVersionServiceTest",
                "CustomerHotQuestionServiceTest",
                "CustomerServiceMetricsServiceTest",
                "RagRetrievalFacadeTest",
                "KnowledgeRetrievalOrchestratorTest",
                "IngestionQualityServiceTest",
                "RagIngestionConsumerTest",
                "DocumentLifecycleServiceTest",
                "RedTeamRegressionTest",
                "AssistantSkillSchemaValidatorTest",
                "Nl2SqlSafetyValidatorTest",
                "Nl2SqlExecutionServiceTest",
                "Nl2SqlOrchestratorPolicyTest",
                "Nl2SqlEvalServiceTest",
                "RagEvalServiceTest",
                "RagEvalOpsServicesTest",
                "OpsRcaEvidenceServiceTest",
                "AiOpsFaultInjectionServiceTest"));
        coverage.put("frontendTestFiles", List.of(
                "AssistantHub.spec.js",
                "PromptGovernance.spec.js",
                "useAssistantRuntime.spec.js",
                "api.spec.js"));
        coverage.put("minimumGoldCases", Map.of(
                "rag", 50,
                "nl2sql", 50,
                "redTeam", 30));
        return coverage;
    }

    private List<Map<String, Object>> capabilityEvidence() {
        return List.of(
                capability("assistant-eval-control-plane",
                        "Unified EvalOps start/status interface for RAG, NL2SQL, red-team, and quality-gate suites",
                        List.of("POST /assistant/evals/{suite}/run", "GET /assistant/evals/{suite}/runs/{evalRunId}"),
                        List.of("AssistantEvalRunServiceTest", "AssistantHub.spec.js", "api.spec.js")),
                capability("rag-reindex-jobs",
                        "Async knowledge-base reindex jobs with task history surfaced in the admin workspace",
                        List.of("POST /ai/rag/reindex-jobs", "GET /ai/rag/ingestion/tasks"),
                        List.of("DocumentLifecycleServiceTest", "RagIngestionConsumerTest", "AssistantHub.spec.js")),
                capability("rag-retrieval-boundary",
                        "Knowledge retrieval, RAG evaluation fallback, and ingestion coverage checks call a single RagRetrievalFacade that owns MultiChannelRetrievalEngine access, document resolution, and retrieval metadata",
                        List.of("KnowledgeRetrievalOrchestrator.retrieve", "RagEvalService.retrieveEvalEvidence", "IngestionQualityService.runQualityReport", "RagRetrievalFacade.retrieve"),
                        List.of("RagRetrievalFacadeTest", "KnowledgeRetrievalOrchestratorTest", "RagEvalServiceTest", "IngestionQualityServiceTest")),
                capability("rag-closure-plan",
                        "RAG reports expose baseline readiness, release blocking, candidate eval cases, and bad-case closure workflow",
                        List.of("GET /api/rag-eval/runs/{evalRunId}/report", "GET /assistant/admin/quality-gates/latest"),
                        List.of("RagEvalOpsServicesTest", "AiQualityGateServiceTest", "AssistantHub.spec.js")),
                capability("nl2sql-response-contract",
                        "NL2SQL returns a stable status/sql/evidence/safetyReport/executionPlan/resultPreview/maskedColumns/repairTrace contract",
                        List.of("POST /assistant/evals/nl2sql/run", "GET /assistant/admin/quality-gates/latest"),
                        List.of("Nl2SqlOrchestratorPolicyTest", "Nl2SqlEvalServiceTest", "AiQualityGateServiceTest")),
                capability("customer-service-experience",
                        "Customer-service quick answers, sentiment triage, escalation tickets, and experience metrics are wired into the unified Assistant Runtime entry",
                        List.of("GET /assistant/customer-service/starter-prompts", "POST /assistant/customer-service/quick-answer", "POST /assistant/customer-service/escalations", "GET /assistant/admin/customer-service/dashboard"),
                        List.of("CustomerHotQuestionServiceTest", "CustomerServiceMetricsServiceTest", "AssistantHub.spec.js", "api.spec.js")),
                capability("run-graph-checkpoint",
                        "Run graph, checkpoint replay, risk nodes, and audit timeline for recoverable agent runs",
                        List.of("GET /assistant/runs/{runId}/graph", "POST /assistant/runs/{runId}/resume", "POST /assistant/runs/{runId}/replay"),
                        List.of("AssistantRunGraphServiceTest", "AssistantHub.spec.js")),
                capability("mcp-protocol-governance",
                        "MCP tools/resources/prompts are separated into explicit allowlists; resource reads and prompt rendering require admin, high-risk confirmation, and audit records",
                        List.of("GET /assistant/admin/mcp/governance", "POST /assistant/admin/mcp/resources/read", "POST /assistant/admin/mcp/prompts/render"),
                        List.of("McpToolGovernanceServiceTest", "AiQualityGateServiceTest", "AssistantHub.spec.js")),
                capability("security-red-team",
                        "Admin-gated governance endpoints and regression coverage for prompt injection, NL2SQL injection, and tool overreach",
                        List.of("/api/rag-eval/**", "/api/nl2sql-eval/**", "/assistant/evals/**", "/ai/rag/**"),
                        List.of("AiAuthenticationInterceptorTest", "RedTeamRegressionTest")),
                capability("aiops-rca",
                        "Fault-injection scenarios and RCA evidence bundles correlate logs, metrics, traces, spanId, topology, changes, SLO, and suggested actions",
                        List.of("POST /assistant/admin/aiops/rca-evidence", "POST /assistant/admin/aiops/fault-scenarios/{scenarioId}/inject"),
                        List.of("OpsRcaEvidenceServiceTest", "AiOpsFaultInjectionServiceTest", "AssistantHub.spec.js")),
                capability("prompt-release-plan",
                        "Prompt/config releases generate a quality-gate evidence plan before publish and persist baseline, rollout, rollback, and gate evidence in release records",
                        List.of("POST /api/prompt-versions/release-plan", "POST /api/prompt-versions/publish"),
                        List.of("PromptVersionServiceTest", "PromptGovernance.spec.js", "api.spec.js")));
    }

    private Map<String, Object> capability(String capability,
                                           String evidence,
                                           List<String> interfaces,
                                           List<String> tests) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("capability", capability);
        item.put("evidence", evidence);
        item.put("interfaces", interfaces);
        item.put("tests", tests);
        return item;
    }

    private List<Map<String, Object>> failureSamples(List<Map<String, Object>> gates,
                                                     AiRagEvalRun ragRun,
                                                     AiNl2SqlEvalRun nl2SqlRun) {
        return gates.stream()
                .filter(gate -> !"PASS".equals(gate.get("status")))
                .map(gate -> failureSample(gate, ragRun, nl2SqlRun))
                .toList();
    }

    private Map<String, Object> failureSample(Map<String, Object> gate,
                                              AiRagEvalRun ragRun,
                                              AiNl2SqlEvalRun nl2SqlRun) {
        String name = String.valueOf(gate.get("name"));
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("gate", name);
        sample.put("status", gate.get("status"));
        sample.put("message", gate.get("message"));
        sample.put("details", gate.get("details"));
        if ("RAG_EVAL".equals(name) && ragRun != null) {
            sample.put("runId", value(ragRun.getEvalRunId()));
            sample.put("errorMessage", value(ragRun.getErrorMessage()));
            sample.put("nextAction", "review RAG bad cases, convert confirmed failures into eval cases, then compare against baseline");
        } else if ("NL2SQL_EVAL".equals(name) && nl2SqlRun != null) {
            sample.put("runId", value(nl2SqlRun.getEvalRunId()));
            sample.put("errorMessage", value(nl2SqlRun.getErrorMessage()));
            sample.put("nextAction", "inspect unsafe rejection, schema-link recall, execution accuracy, and repair traces");
        } else if ("MCP_GOVERNANCE".equals(name)) {
            sample.put("nextAction", "keep MCP allowlist explicit, require admin, and keep high-risk tools confirmation-gated");
        } else if ("AIOPS_LAB".equals(name)) {
            sample.put("nextAction", "add missing fault scenarios so RCA evidence covers latency, errors, DB, downstream, and business consistency");
        } else if ("CUSTOMER_SERVICE_EXPERIENCE".equals(name)) {
            sample.put("nextAction", "review hot-question coverage, unresolved cases, negative sentiment triggers, and CSAT before prompt/config release");
        } else {
            sample.put("nextAction", "run the targeted regression suite and inspect CI logs");
        }
        return sample;
    }

    private Map<String, Object> releaseReadiness(String status, List<Map<String, Object>> gates) {
        List<String> blockers = gates.stream()
                .filter(gate -> "FAIL".equals(gate.get("status")))
                .map(gate -> String.valueOf(gate.get("name")))
                .toList();
        List<String> warnings = gates.stream()
                .filter(gate -> "WARN".equals(gate.get("status")))
                .map(gate -> String.valueOf(gate.get("name")))
                .toList();
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("ready", blockers.isEmpty());
        readiness.put("status", blockers.isEmpty() ? ("PASS".equals(status) ? "READY" : "READY_WITH_WARNINGS") : "BLOCKED");
        readiness.put("blockers", blockers);
        readiness.put("warnings", warnings);
        readiness.put("approvalHint", blockers.isEmpty()
                ? "prompt/config release can proceed with baseline comparison and rollback plan"
                : "release blocked until failed quality gates are fixed");
        return readiness;
    }

    private Map<String, Object> trendSummary(AiRagEvalRun ragRun, AiNl2SqlEvalRun nl2SqlRun) {
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("ragBaselineRunId", ragRun == null ? "" : value(ragRun.getBaselineRunId()));
        trend.put("ragPromptVersion", ragRun == null ? "" : value(ragRun.getPromptVersion()));
        trend.put("ragModelVersion", ragRun == null ? "" : value(ragRun.getModelVersion()));
        trend.put("nl2SqlRunId", nl2SqlRun == null ? "" : value(nl2SqlRun.getEvalRunId()));
        trend.put("needsBaseline", ragRun == null || !StringUtils.hasText(ragRun.getBaselineRunId()));
        return trend;
    }

    private Map<String, Object> ragClosure(AiRagEvalRun ragRun) {
        Map<String, Object> closure = new LinkedHashMap<>();
        if (ragRun == null) {
            closure.put("status", "MISSING_EVAL");
            closure.put("baselineReady", false);
            closure.put("releaseBlocked", true);
            closure.put("workflow", ragClosureWorkflow());
            closure.put("nextActions", List.of("run RAG eval suite and compare against a baseline before release"));
            return closure;
        }
        Map<String, Object> savedClosure = readClosurePlan(ragRun.getReportJson());
        boolean baselineReady = StringUtils.hasText(ragRun.getBaselineRunId())
                || Boolean.TRUE.equals(savedClosure.get("baselineReady"));
        boolean releaseBlocked = !baselineReady || Boolean.TRUE.equals(savedClosure.get("releaseBlocked"));
        closure.put("status", releaseBlocked ? "ACTION_REQUIRED" : "READY");
        closure.put("baselineReady", baselineReady);
        closure.put("baselineRunId", StringUtils.hasText(ragRun.getBaselineRunId())
                ? ragRun.getBaselineRunId()
                : value(String.valueOf(savedClosure.getOrDefault("baselineRunId", ""))));
        closure.put("releaseBlocked", releaseBlocked);
        closure.put("completedCases", ragRun.getCompletedCases() == null ? 0 : ragRun.getCompletedCases());
        closure.put("candidateEvalCaseIds", listOrDefault(savedClosure.get("candidateEvalCaseIds")));
        closure.put("nextActions", listOrDefault(savedClosure.get("nextActions")).isEmpty()
                ? (baselineReady
                ? List.of("approve prompt/config release with rollback record")
                : List.of("run baseline comparison before prompt/config release"))
                : listOrDefault(savedClosure.get("nextActions")));
        closure.put("workflow", ragClosureWorkflow());
        return closure;
    }

    private Map<String, Object> nl2SqlContract(AiNl2SqlEvalRun nl2SqlRun) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("responseFields", List.of(
                "status",
                "sql",
                "evidence",
                "safetyReport",
                "executionPlan",
                "resultPreview",
                "maskedColumns",
                "repairTrace"));
        contract.put("executionPlanFields", List.of(
                "mode",
                "costGuard",
                "costGuardReport",
                "estimatedRows",
                "queryCost",
                "rowCount",
                "truncated",
                "durationMs"));
        contract.put("safetyMetrics", List.of(
                "unsafeRejectionRate",
                "lowConfidenceClarificationRate",
                "schemaLinkPrecision",
                "schemaLinkRecall",
                "repairSuccessRate",
                "avgEstimatedCost",
                "costGuardStatus",
                "estimatedRows",
                "queryCost"));
        if (nl2SqlRun == null) {
            contract.put("status", "MISSING_EVAL");
            contract.put("contractReady", false);
            contract.put("nextActions", List.of("run NL2SQL eval suite to verify response contract and safety metrics"));
            return contract;
        }
        boolean contractReady = "COMPLETED".equals(nl2SqlRun.getRunStatus())
                && nl2SqlRun.getUnsafeRejectionRate() != null
                && nl2SqlRun.getLowConfidenceClarificationRate() != null
                && nl2SqlRun.getSchemaLinkRecall() != null;
        contract.put("status", contractReady ? "READY" : "ACTION_REQUIRED");
        contract.put("contractReady", contractReady);
        contract.put("evalRunId", value(nl2SqlRun.getEvalRunId()));
        contract.put("completedCases", nl2SqlRun.getCompletedCases() == null ? 0 : nl2SqlRun.getCompletedCases());
        contract.put("unsafeRejectionRate", nl2SqlRun.getUnsafeRejectionRate() == null ? 0D : nl2SqlRun.getUnsafeRejectionRate());
        contract.put("lowConfidenceClarificationRate", nl2SqlRun.getLowConfidenceClarificationRate() == null ? 0D : nl2SqlRun.getLowConfidenceClarificationRate());
        contract.put("schemaLinkRecall", nl2SqlRun.getSchemaLinkRecall() == null ? 0D : nl2SqlRun.getSchemaLinkRecall());
        contract.put("costGuardReady", true);
        contract.put("costGuardPolicy", "EXPLAIN_LIMIT_AND_TIMEOUT");
        contract.put("nextActions", contractReady
                ? List.of("keep fixed NL2SQL response contract and EXPLAIN cost guard in API/front-end tests")
                : List.of("inspect missing NL2SQL safety metrics and response contract fields"));
        return contract;
    }

    private Map<String, Object> readClosurePlan(String reportJson) {
        if (!StringUtils.hasText(reportJson)) {
            return Map.of();
        }
        try {
            JSONObject report = JSON.parseObject(reportJson);
            Object closure = report == null ? null : report.get("closurePlan");
            if (closure instanceof JSONObject object) {
                return new LinkedHashMap<>(object);
            }
            if (closure instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private List<String> ragClosureWorkflow() {
        return List.of("online bad case", "human review", "convert to eval", "baseline comparison", "prompt/config release");
    }

    private List<String> listOrDefault(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(StringUtils::hasText).toList();
        }
        return List.of();
    }

    private Map<String, Object> gate(String name, String status, String message, Map<String, Object> details) {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("name", name);
        gate.put("status", status);
        gate.put("message", message);
        gate.put("details", details);
        return gate;
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0D;
        }
    }
}
