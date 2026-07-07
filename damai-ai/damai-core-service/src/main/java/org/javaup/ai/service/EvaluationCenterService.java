package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiNl2SqlEvalResult;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.entity.AiPurchaseAgentEvalRun;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.enums.EvaluationDomain;
import org.javaup.ai.mapper.AiNl2SqlEvalResultMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.javaup.ai.vo.EvaluationRunRequest;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EvaluationCenterService {

    private final RagEvalService ragEvalService;
    private final Nl2SqlEvalService nl2SqlEvalService;
    private final PurchaseAgentEvalService purchaseAgentEvalService;
    private final AiQualityGateService qualityGateService;
    private final CustomerServiceMetricsService customerServiceMetricsService;
    private final AiRagEvalRunMapper ragRunMapper;
    private final AiRagEvalResultMapper ragResultMapper;
    private final AiNl2SqlEvalRunMapper nl2SqlRunMapper;
    private final AiNl2SqlEvalResultMapper nl2SqlResultMapper;

    public Map<String, Object> startRun(EvaluationRunRequest request) {
        EvaluationDomain domain = EvaluationDomain.from(request == null ? null : request.getDomain());
        return switch (domain) {
            case RAG_CUSTOMER -> startRag(request);
            case PURCHASE_AGENT -> startPurchase(request);
            case NL2SQL_ADMIN -> startNl2Sql(request);
        };
    }

    public Map<String, Object> getRun(String evalRunId) {
        Map<String, Object> response = new LinkedHashMap<>();
        AiRagEvalRun ragRun = ragEvalService.getRunStatus(evalRunId);
        if (ragRun != null) {
            response.put("domain", EvaluationDomain.RAG_CUSTOMER.name());
            response.put("run", ragRun);
            response.put("qualityGate", ragEvalService.buildQualityGate(ragRun));
            return response;
        }
        AiPurchaseAgentEvalRun purchaseRun = purchaseAgentEvalService.getRunStatus(evalRunId);
        if (purchaseRun != null) {
            response.put("domain", EvaluationDomain.PURCHASE_AGENT.name());
            response.put("run", purchaseRun);
            response.put("qualityGate", purchaseAgentEvalService.buildQualityGate(purchaseRun));
            return response;
        }
        AiNl2SqlEvalRun nl2SqlRun = nl2SqlEvalService.getRunStatus(evalRunId);
        if (nl2SqlRun != null) {
            response.put("domain", EvaluationDomain.NL2SQL_ADMIN.name());
            response.put("run", nl2SqlRun);
            response.put("qualityGate", nl2SqlGate(nl2SqlRun));
            return response;
        }
        response.put("message", "not found");
        return response;
    }

    public Map<String, Object> getRunResults(String evalRunId) {
        Map<String, Object> response = getRun(evalRunId);
        Object domain = response.get("domain");
        if (EvaluationDomain.RAG_CUSTOMER.name().equals(domain)) {
            response.put("results", ragEvalService.listRunResults(evalRunId));
        } else if (EvaluationDomain.PURCHASE_AGENT.name().equals(domain)) {
            response.put("results", purchaseAgentEvalService.listRunResults(evalRunId));
        } else if (EvaluationDomain.NL2SQL_ADMIN.name().equals(domain)) {
            response.put("results", nl2SqlEvalService.listRunResults(evalRunId));
        } else {
            response.put("results", List.of());
        }
        return response;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("qualityGate", qualityGateService.latestGate());
        dashboard.put("ragCustomer", ragDashboard());
        dashboard.put("purchaseAgent", purchaseAgentEvalService.dashboard());
        dashboard.put("nl2SqlAdmin", nl2SqlDashboard());
        dashboard.put("customerService", customerServiceMetricsService.dashboard());
        dashboard.put("failureSamples", failureSamples());
        return dashboard;
    }

    public Map<String, Object> replayFailed(String evalRunId) {
        Map<String, Object> run = getRun(evalRunId);
        Object domain = run.get("domain");
        if (EvaluationDomain.PURCHASE_AGENT.name().equals(domain)) {
            return purchaseAgentEvalService.replayFailed(evalRunId);
        }
        if (EvaluationDomain.RAG_CUSTOMER.name().equals(domain)) {
            List<AiRagEvalResult> failed = ragEvalService.listRunResults(evalRunId).stream()
                    .filter(result -> zero(result.getRecallAt5()) < 0.75D
                            || zero(result.getMrr()) < 0.55D
                            || zero(result.getCitationCoverage()) < 0.80D)
                    .toList();
            return Map.of("evalRunId", evalRunId, "domain", domain, "replayed", failed.size(),
                    "message", "RAG failed cases are ready for diagnosis or bad-case conversion",
                    "cases", failed.stream().map(AiRagEvalResult::getCaseId).toList());
        }
        if (EvaluationDomain.NL2SQL_ADMIN.name().equals(domain)) {
            List<AiNl2SqlEvalResult> failed = nl2SqlEvalService.listRunResults(evalRunId).stream()
                    .filter(result -> result.getUnsafeRejected() == null && result.getResultSetEquivalent() == null
                            || Integer.valueOf(0).equals(result.getResultSetEquivalent())
                            || Integer.valueOf(0).equals(result.getIsValidSql()))
                    .toList();
            return Map.of("evalRunId", evalRunId, "domain", domain, "replayed", failed.size(),
                    "message", "NL2SQL failed cases are ready for SQL repair or safety review",
                    "cases", failed.stream().map(AiNl2SqlEvalResult::getCaseId).toList());
        }
        return Map.of("evalRunId", evalRunId, "message", "run not found", "replayed", 0);
    }

    private Map<String, Object> startRag(EvaluationRunRequest request) {
        RagEvalRunRequest ragRequest = new RagEvalRunRequest();
        ragRequest.setDatasetId(request.getDatasetId());
        ragRequest.setDatasetVersion(request.getDatasetVersion());
        ragRequest.setCategory(request.getCategory());
        ragRequest.setDifficulty(request.getDifficulty());
        ragRequest.setCaseIds(request.getCaseIds());
        ragRequest.setLimit(request.getLimit());
        ragRequest.setBaselineRunId(request.getBaselineRunId());
        ragRequest.setRetrievalConfigId(request.getCandidateConfigId());
        ragRequest.setPromptVersion(request.getPromptVersion());
        ragRequest.setModelVersion(request.getModelVersion());
        AiRagEvalRun run = ragEvalService.startEvaluation(ragRequest);
        return Map.of("domain", EvaluationDomain.RAG_CUSTOMER.name(),
                "evalRunId", run.getEvalRunId(),
                "runStatus", run.getRunStatus(),
                "totalCases", run.getTotalCases());
    }

    private Map<String, Object> startPurchase(EvaluationRunRequest request) {
        AiPurchaseAgentEvalRun run = purchaseAgentEvalService.startEvaluation(request);
        return Map.of("domain", EvaluationDomain.PURCHASE_AGENT.name(),
                "evalRunId", run.getEvalRunId(),
                "runStatus", run.getRunStatus(),
                "totalCases", run.getTotalCases(),
                "qualityGate", purchaseAgentEvalService.buildQualityGate(run));
    }

    private Map<String, Object> startNl2Sql(EvaluationRunRequest request) {
        AiNl2SqlEvalRun run = nl2SqlEvalService.startEvaluation(request.getCategory(), request.getDifficulty(), request.getCaseIds());
        return Map.of("domain", EvaluationDomain.NL2SQL_ADMIN.name(),
                "evalRunId", run.getEvalRunId(),
                "runStatus", run.getRunStatus(),
                "totalCases", run.getTotalCases());
    }

    private Map<String, Object> ragDashboard() {
        AiRagEvalRun latest = latestRagRun();
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("latestRun", latest);
        dashboard.put("qualityGate", latest == null ? Map.of("status", "WARN", "message", "no RAG eval run found") : ragEvalService.buildQualityGate(latest));
        dashboard.put("trend", latestRagRuns().stream().map(this::ragSummary).toList());
        return dashboard;
    }

    private Map<String, Object> nl2SqlDashboard() {
        AiNl2SqlEvalRun latest = latestNl2SqlRun();
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("latestRun", latest);
        dashboard.put("qualityGate", latest == null ? Map.of("status", "WARN", "message", "no NL2SQL eval run found") : nl2SqlGate(latest));
        dashboard.put("trend", latestNl2SqlRuns().stream().map(this::nl2SqlSummary).toList());
        return dashboard;
    }

    private List<Map<String, Object>> failureSamples() {
        return List.of(
                Map.of("domain", EvaluationDomain.RAG_CUSTOMER.name(), "samples", latestRagFailures()),
                Map.of("domain", EvaluationDomain.PURCHASE_AGENT.name(), "samples", purchaseAgentEvalService.dashboard().get("failureSamples")),
                Map.of("domain", EvaluationDomain.NL2SQL_ADMIN.name(), "samples", latestNl2SqlFailures())
        );
    }

    private AiRagEvalRun latestRagRun() {
        return ragRunMapper.selectOne(new LambdaQueryWrapper<AiRagEvalRun>()
                .eq(AiRagEvalRun::getStatus, 1).orderByDesc(AiRagEvalRun::getId).last("limit 1"));
    }

    private List<AiRagEvalRun> latestRagRuns() {
        return ragRunMapper.selectList(new LambdaQueryWrapper<AiRagEvalRun>()
                .eq(AiRagEvalRun::getStatus, 1).orderByDesc(AiRagEvalRun::getId).last("limit 7"));
    }

    private AiNl2SqlEvalRun latestNl2SqlRun() {
        return nl2SqlRunMapper.selectOne(new LambdaQueryWrapper<AiNl2SqlEvalRun>()
                .eq(AiNl2SqlEvalRun::getStatus, 1).orderByDesc(AiNl2SqlEvalRun::getId).last("limit 1"));
    }

    private List<AiNl2SqlEvalRun> latestNl2SqlRuns() {
        return nl2SqlRunMapper.selectList(new LambdaQueryWrapper<AiNl2SqlEvalRun>()
                .eq(AiNl2SqlEvalRun::getStatus, 1).orderByDesc(AiNl2SqlEvalRun::getId).last("limit 7"));
    }

    private Map<String, Object> ragSummary(AiRagEvalRun run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evalRunId", run.getEvalRunId());
        summary.put("runStatus", run.getRunStatus());
        summary.put("avgRecall", zero(run.getAvgRecall()));
        summary.put("avgMrr", zero(run.getAvgMrr()));
        summary.put("avgFaithfulness", zero(run.getAvgFaithfulness()));
        summary.put("avgAnswerRelevancy", zero(run.getAvgAnswerRelevancy()));
        return summary;
    }

    private Map<String, Object> nl2SqlSummary(AiNl2SqlEvalRun run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evalRunId", run.getEvalRunId());
        summary.put("runStatus", run.getRunStatus());
        summary.put("sqlValidityRate", zero(run.getSqlValidityRate()));
        summary.put("executionAccuracy", zero(run.getExecutionAccuracy()));
        summary.put("resultSetEquivalenceRate", zero(run.getResultSetEquivalenceRate()));
        summary.put("unsafeRejectionRate", zero(run.getUnsafeRejectionRate()));
        summary.put("avgLatencyMs", zero(run.getAvgLatencyMs()));
        return summary;
    }

    private Map<String, Object> nl2SqlGate(AiNl2SqlEvalRun run) {
        String status = "COMPLETED".equals(run.getRunStatus())
                && zero(run.getUnsafeRejectionRate()) >= 1D
                && zero(run.getResultSetEquivalenceRate()) >= 0.75D
                && zero(run.getSqlValidityRate()) >= 0.90D ? "PASS" : "FAIL";
        return Map.of("status", status, "details", nl2SqlSummary(run));
    }

    private List<Map<String, Object>> latestRagFailures() {
        AiRagEvalRun latest = latestRagRun();
        if (latest == null || !StringUtils.hasText(latest.getEvalRunId())) {
            return List.of();
        }
        return ragResultMapper.selectList(new LambdaQueryWrapper<AiRagEvalResult>()
                        .eq(AiRagEvalResult::getEvalRunId, latest.getEvalRunId())
                        .eq(AiRagEvalResult::getStatus, 1)
                        .orderByDesc(AiRagEvalResult::getId)
                        .last("limit 20"))
                .stream()
                .filter(result -> zero(result.getRecallAt5()) < 0.75D || zero(result.getCitationCoverage()) < 0.80D)
                .limit(5)
                .map(result -> Map.<String, Object>of("caseId", result.getCaseId(), "question", value(result.getQuestion()), "failureType", value(result.getFailureType())))
                .toList();
    }

    private List<Map<String, Object>> latestNl2SqlFailures() {
        AiNl2SqlEvalRun latest = latestNl2SqlRun();
        if (latest == null || !StringUtils.hasText(latest.getEvalRunId())) {
            return List.of();
        }
        return nl2SqlResultMapper.selectList(new LambdaQueryWrapper<AiNl2SqlEvalResult>()
                        .eq(AiNl2SqlEvalResult::getEvalRunId, latest.getEvalRunId())
                        .eq(AiNl2SqlEvalResult::getStatus, 1)
                        .orderByDesc(AiNl2SqlEvalResult::getId)
                        .last("limit 20"))
                .stream()
                .filter(result -> Integer.valueOf(0).equals(result.getResultSetEquivalent()) || Integer.valueOf(0).equals(result.getIsValidSql()))
                .limit(5)
                .map(result -> Map.<String, Object>of("caseId", result.getCaseId(), "question", value(result.getQuestion()), "errorMessage", value(result.getErrorMessage())))
                .toList();
    }

    private double zero(Double value) {
        return value == null ? 0D : value;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
