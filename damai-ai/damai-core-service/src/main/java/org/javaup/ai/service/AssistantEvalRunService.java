package org.javaup.ai.service;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.vo.AssistantEvalRunRequest;
import org.javaup.ai.vo.AssistantEvalRunVo;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantEvalRunService {

    private final RagEvalService ragEvalService;
    private final Nl2SqlEvalService nl2SqlEvalService;
    private final AiQualityGateService qualityGateService;

    public AssistantEvalRunVo runSuite(String suite, AssistantEvalRunRequest request) {
        String normalizedSuite = normalizeSuite(suite);
        AssistantEvalRunRequest normalizedRequest = request == null ? new AssistantEvalRunRequest() : request;
        return switch (normalizedSuite) {
            case "RAG" -> runRag(normalizedRequest);
            case "NL2SQL" -> runNl2Sql(normalizedRequest);
            case "RED_TEAM" -> runGovernanceSnapshot("RED_TEAM", "QUALITY_GATE_RED_TEAM");
            case "QUALITY_GATE" -> runGovernanceSnapshot("QUALITY_GATE", "QUALITY_GATE_SNAPSHOT");
            default -> throw new IllegalArgumentException("Unsupported eval suite: " + suite);
        };
    }

    public AssistantEvalRunVo getSuiteRun(String suite, String evalRunId) {
        String normalizedSuite = normalizeSuite(suite);
        return switch (normalizedSuite) {
            case "RAG" -> buildRagStatus(evalRunId);
            case "NL2SQL" -> buildNl2SqlStatus(evalRunId);
            case "RED_TEAM" -> runGovernanceSnapshot("RED_TEAM", "QUALITY_GATE_RED_TEAM");
            case "QUALITY_GATE" -> runGovernanceSnapshot("QUALITY_GATE", "QUALITY_GATE_SNAPSHOT");
            default -> throw new IllegalArgumentException("Unsupported eval suite: " + suite);
        };
    }

    private AssistantEvalRunVo runRag(AssistantEvalRunRequest request) {
        RagEvalRunRequest ragRequest = new RagEvalRunRequest();
        ragRequest.setDatasetId(request.getDatasetId());
        ragRequest.setDatasetVersion(request.getDatasetVersion());
        ragRequest.setCategory(request.getCategory());
        ragRequest.setDifficulty(request.getDifficulty());
        ragRequest.setCaseIds(request.getCaseIds());
        ragRequest.setLimit(request.getLimit());
        ragRequest.setBaselineRunId(request.getBaselineRunId());
        ragRequest.setPromptVersion(request.getPromptVersion());
        ragRequest.setModelVersion(request.getModelVersion());
        AiRagEvalRun run = ragEvalService.startEvaluation(ragRequest);
        return AssistantEvalRunVo.builder()
                .suite("RAG")
                .status(run.getRunStatus())
                .evalRunId(run.getEvalRunId())
                .totalCases(run.getTotalCases())
                .completedCases(run.getCompletedCases())
                .progress(progress(run.getCompletedCases(), run.getTotalCases()))
                .metrics(Map.of())
                .resultType("RAG_EVAL_RUN")
                .qualityGate(Map.of("status", "RUNNING", "message", "RAG eval started; poll report or quality gate for final metrics"))
                .nextActions(List.of(
                        "Poll /api/rag-eval/status/" + run.getEvalRunId(),
                        "Review bad cases after completion",
                        "Compare with baseline before prompt/config release"))
                .build();
    }

    private AssistantEvalRunVo runNl2Sql(AssistantEvalRunRequest request) {
        AiNl2SqlEvalRun run = nl2SqlEvalService.startEvaluation(
                request.getCategory(),
                request.getDifficulty(),
                request.getCaseIds());
        return AssistantEvalRunVo.builder()
                .suite("NL2SQL")
                .status(run.getRunStatus())
                .evalRunId(run.getEvalRunId())
                .totalCases(run.getTotalCases())
                .completedCases(run.getCompletedCases())
                .progress(progress(run.getCompletedCases(), run.getTotalCases()))
                .metrics(Map.of())
                .resultType("NL2SQL_EVAL_RUN")
                .qualityGate(Map.of("status", "RUNNING", "message", "NL2SQL eval started; inspect execution accuracy and unsafe rejection rate"))
                .nextActions(List.of(
                        "Poll /api/nl2sql-eval/status/" + run.getEvalRunId(),
                        "Inspect schema-link precision/recall",
                        "Block release if unsafe rejection or execution accuracy regresses"))
                .build();
    }

    private AssistantEvalRunVo runGovernanceSnapshot(String suite, String resultType) {
        Map<String, Object> gate = qualityGateService.latestGate();
        return AssistantEvalRunVo.builder()
                .suite(suite)
                .status(String.valueOf(gate.getOrDefault("status", "UNKNOWN")))
                .evalRunId(String.valueOf(gate.getOrDefault("reportId", suite.toLowerCase() + "-snapshot")))
                .totalCases(null)
                .completedCases(null)
                .progress(null)
                .metrics(Map.of())
                .resultType(resultType)
                .qualityGate(gate)
                .nextActions(List.of(
                        "Keep red-team, RAG, NL2SQL, MCP, and AIOps gates in CI",
                        "Review failureSamples before releasing prompt/config changes",
                        "Use latest quality gate as the release readiness source"))
                .build();
    }

    private AssistantEvalRunVo buildRagStatus(String evalRunId) {
        AiRagEvalRun run = ragEvalService.getRunStatus(evalRunId);
        if (run == null) {
            return null;
        }
        return AssistantEvalRunVo.builder()
                .suite("RAG")
                .status(run.getRunStatus())
                .evalRunId(run.getEvalRunId())
                .totalCases(run.getTotalCases())
                .completedCases(run.getCompletedCases())
                .progress(progress(run.getCompletedCases(), run.getTotalCases()))
                .resultType("RAG_EVAL_RUN")
                .metrics(Map.of(
                        "avgRecall", zeroIfNull(run.getAvgRecall()),
                        "avgFaithfulness", zeroIfNull(run.getAvgFaithfulness()),
                        "avgAnswerCorrectness", zeroIfNull(run.getAvgAnswerCorrectness()),
                        "avgCtxPrecision", zeroIfNull(run.getAvgCtxPrecision()),
                        "avgCtxRecall", zeroIfNull(run.getAvgCtxRecall())))
                .qualityGate(ragEvalService.buildQualityGate(run))
                .nextActions(ragNextActions(run))
                .build();
    }

    private AssistantEvalRunVo buildNl2SqlStatus(String evalRunId) {
        AiNl2SqlEvalRun run = nl2SqlEvalService.getRunStatus(evalRunId);
        if (run == null) {
            return null;
        }
        return AssistantEvalRunVo.builder()
                .suite("NL2SQL")
                .status(run.getRunStatus())
                .evalRunId(run.getEvalRunId())
                .totalCases(run.getTotalCases())
                .completedCases(run.getCompletedCases())
                .progress(progress(run.getCompletedCases(), run.getTotalCases()))
                .resultType("NL2SQL_EVAL_RUN")
                .metrics(Map.of(
                        "sqlValidityRate", zeroIfNull(run.getSqlValidityRate()),
                        "executionAccuracy", zeroIfNull(run.getExecutionAccuracy()),
                        "resultSetEquivalenceRate", zeroIfNull(run.getResultSetEquivalenceRate()),
                        "schemaLinkPrecision", zeroIfNull(run.getSchemaLinkPrecision()),
                        "schemaLinkRecall", zeroIfNull(run.getSchemaLinkRecall()),
                        "unsafeRejectionRate", zeroIfNull(run.getUnsafeRejectionRate()),
                        "repairSuccessRate", zeroIfNull(run.getRepairSuccessRate()),
                        "avgLatencyMs", zeroIfNull(run.getAvgLatencyMs())))
                .qualityGate(Map.of("status", nl2SqlGateStatus(run)))
                .nextActions(nl2SqlNextActions(run))
                .build();
    }

    private List<String> ragNextActions(AiRagEvalRun run) {
        if (!"COMPLETED".equals(run.getRunStatus())) {
            return List.of("Wait for RAG eval completion before comparing baseline");
        }
        return List.of(
                "Open RAG report and inspect structured judge failures",
                "Convert confirmed bad cases into golden eval cases",
                "Compare with baseline before releasing prompt/config changes");
    }

    private List<String> nl2SqlNextActions(AiNl2SqlEvalRun run) {
        if (!"COMPLETED".equals(run.getRunStatus())) {
            return List.of("Wait for NL2SQL eval completion before release decision");
        }
        return List.of(
                "Inspect unsafe rejection and low-confidence clarification cases",
                "Review schema-link precision/recall regressions",
                "Block release if execution accuracy or safety thresholds fail");
    }

    private String nl2SqlGateStatus(AiNl2SqlEvalRun run) {
        boolean pass = zeroIfNull(run.getSqlValidityRate()) >= 0.9
                && zeroIfNull(run.getExecutionAccuracy()) >= 0.75
                && zeroIfNull(run.getSchemaLinkRecall()) >= 0.8
                && zeroIfNull(run.getUnsafeRejectionRate()) >= 0.95;
        return pass ? "PASS" : "FAIL";
    }

    private Double progress(Integer completedCases, Integer totalCases) {
        if (completedCases == null || totalCases == null || totalCases <= 0) {
            return 0D;
        }
        return Math.min(1D, Math.max(0D, completedCases * 1.0 / totalCases));
    }

    private Double zeroIfNull(Double value) {
        return value == null ? 0D : value;
    }

    private String normalizeSuite(String suite) {
        if (!StringUtils.hasText(suite)) {
            return "";
        }
        return suite.trim()
                .replace('-', '_')
                .toUpperCase();
    }
}
