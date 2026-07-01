package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagEvalReportService {

    private final AiRagEvalRunMapper runMapper;
    private final AiRagEvalResultMapper resultMapper;
    private final AiRagEvalCaseMapper caseMapper;
    private final RagEvalService ragEvalService;

    public Map<String, Object> getRunReport(String evalRunId) {
        AiRagEvalRun run = getRun(evalRunId);
        if (run == null) {
            return null;
        }
        List<AiRagEvalResult> results = listResults(evalRunId);
        Map<String, AiRagEvalCase> caseLookup = loadCaseLookup(results);
        Map<String, Object> report = new LinkedHashMap<>();
        if (StringUtils.hasText(run.getReportJson())) {
            try {
                JSONObject saved = JSON.parseObject(run.getReportJson());
                if (saved != null) {
                    report.putAll(saved);
                }
            } catch (Exception ignored) {
            }
        }
        report.put("evalRunId", run.getEvalRunId());
        report.put("datasetId", run.getDatasetId());
        report.put("datasetVersion", run.getDatasetVersion());
        report.put("retrievalConfigId", run.getRetrievalConfigId());
        report.put("judgeConfigId", run.getJudgeConfigId());
        report.put("baselineRunId", run.getBaselineRunId());
        report.put("status", run.getRunStatus());
        report.put("totalCases", run.getTotalCases());
        report.put("completedCases", run.getCompletedCases());
        report.put("errorMessage", run.getErrorMessage());
        report.put("qualityGate", parseQualityGate(run));
        report.put("summaryMetrics", buildSummaryMetrics(run));
        report.put("categoryBreakdown", buildBreakdown(results, caseLookup, BreakdownField.CATEGORY));
        report.put("difficultyBreakdown", buildBreakdown(results, caseLookup, BreakdownField.DIFFICULTY));
        report.put("caseTypeBreakdown", buildBreakdown(results, caseLookup, BreakdownField.CASE_TYPE));
        report.put("failureTypeDistribution", results.stream().collect(Collectors.groupingBy(
                result -> StringUtils.hasText(result.getFailureType()) ? result.getFailureType() : "NONE",
                LinkedHashMap::new,
                Collectors.counting())));
        report.put("structuredJudgeSummary", buildStructuredJudgeSummary(results));
        report.put("latencySummary", buildLatencySummary(results));
        List<Map<String, Object>> topBadCases = buildTopBadCases(results, caseLookup);
        report.put("topBadCases", topBadCases);
        report.put("closurePlan", buildClosurePlan(run, results, topBadCases));
        report.put("resultCount", results.size());
        return report;
    }

    private AiRagEvalRun getRun(String evalRunId) {
        if (!StringUtils.hasText(evalRunId)) {
            return null;
        }
        return runMapper.selectOne(new LambdaQueryWrapper<AiRagEvalRun>()
                .eq(AiRagEvalRun::getEvalRunId, evalRunId)
                .last("limit 1"));
    }

    private List<AiRagEvalResult> listResults(String evalRunId) {
        return resultMapper.selectList(new LambdaQueryWrapper<AiRagEvalResult>()
                .eq(AiRagEvalResult::getEvalRunId, evalRunId)
                .orderByDesc(AiRagEvalResult::getId));
    }

    private Map<String, AiRagEvalCase> loadCaseLookup(List<AiRagEvalResult> results) {
        List<String> caseIds = results.stream()
                .map(AiRagEvalResult::getCaseId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        return caseMapper.selectList(new LambdaQueryWrapper<AiRagEvalCase>()
                        .eq(AiRagEvalCase::getStatus, 1)
                        .in(AiRagEvalCase::getCaseId, caseIds))
                .stream()
                .collect(Collectors.toMap(AiRagEvalCase::getCaseId, item -> item, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, Object> parseQualityGate(AiRagEvalRun run) {
        if (StringUtils.hasText(run.getQualityGateJson())) {
            try {
                JSONObject saved = JSON.parseObject(run.getQualityGateJson());
                if (saved != null) {
                    return new LinkedHashMap<>(saved);
                }
            } catch (Exception ignored) {
            }
        }
        return ragEvalService.buildQualityGate(run);
    }

    private Map<String, Object> buildSummaryMetrics(AiRagEvalRun run) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("avgRecall", run.getAvgRecall());
        metrics.put("avgPrecision", run.getAvgPrecision());
        metrics.put("avgHitRate", run.getAvgHitRate());
        metrics.put("avgMrr", run.getAvgMrr());
        metrics.put("avgNdcg", run.getAvgNdcg());
        metrics.put("avgCtxPrecision", run.getAvgCtxPrecision());
        metrics.put("avgCtxRecall", run.getAvgCtxRecall());
        metrics.put("avgContextRelevance", run.getAvgContextRelevance());
        metrics.put("avgFaithfulness", run.getAvgFaithfulness());
        metrics.put("avgAnswerRelevancy", run.getAvgAnswerRelevancy());
        metrics.put("avgAnswerCorrectness", run.getAvgAnswerCorrectness());
        return metrics;
    }

    private Map<String, Long> buildBreakdown(List<AiRagEvalResult> results,
                                             Map<String, AiRagEvalCase> caseLookup,
                                             BreakdownField field) {
        return results.stream().collect(Collectors.groupingBy(result -> {
            AiRagEvalCase evalCase = caseLookup.get(result.getCaseId());
            if (field == BreakdownField.CASE_TYPE) {
                String caseType = result.getCaseType();
                if (!StringUtils.hasText(caseType) && evalCase != null) {
                    caseType = evalCase.getCaseType();
                }
                return StringUtils.hasText(caseType) ? caseType : "UNSPECIFIED";
            }
            if (evalCase == null) {
                return "UNSPECIFIED";
            }
            if (field == BreakdownField.CATEGORY) {
                return StringUtils.hasText(evalCase.getCategory()) ? evalCase.getCategory() : "UNSPECIFIED";
            }
            return StringUtils.hasText(evalCase.getDifficulty()) ? evalCase.getDifficulty() : "UNSPECIFIED";
        }, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, Object> buildLatencySummary(List<AiRagEvalResult> results) {
        List<Long> latencies = results.stream()
                .map(result -> result.getTotalLatencyMs() != null ? result.getTotalLatencyMs() : result.getLatencyMs())
                .filter(v -> v != null && v >= 0)
                .sorted()
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", latencies.size());
        summary.put("avgMs", latencies.isEmpty() ? null : latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        summary.put("p50Ms", percentile(latencies, 0.50));
        summary.put("p95Ms", percentile(latencies, 0.95));
        summary.put("p99Ms", percentile(latencies, 0.99));
        return summary;
    }

    private Long percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(quantile * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        return sortedValues.get(index);
    }

    private List<Map<String, Object>> buildTopBadCases(List<AiRagEvalResult> results,
                                                       Map<String, AiRagEvalCase> caseLookup) {
        return results.stream()
                .sorted(Comparator.comparingDouble(this::scoreResult))
                .limit(10)
                .map(result -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    AiRagEvalCase evalCase = caseLookup.get(result.getCaseId());
                    item.put("caseId", result.getCaseId());
                    item.put("question", result.getQuestion());
                    item.put("category", evalCase != null ? evalCase.getCategory() : null);
                    item.put("difficulty", evalCase != null ? evalCase.getDifficulty() : null);
                    item.put("caseType", StringUtils.hasText(result.getCaseType()) ? result.getCaseType() : evalCase != null ? evalCase.getCaseType() : null);
                    item.put("recallAt5", result.getRecallAt5());
                    item.put("faithfulness", result.getFaithfulnessScore());
                    item.put("answerCorrectness", result.getAnswerCorrectnessScore());
                    item.put("judgeRelevance", result.getJudgeRelevance());
                    item.put("judgeCoverage", result.getJudgeCoverage());
                    item.put("judgeContradiction", result.getJudgeContradiction());
                    item.put("judgeCitationSupport", result.getJudgeCitationSupport());
                    item.put("judgeAnswerability", result.getJudgeAnswerability());
                    item.put("judgeRefusalReason", result.getJudgeRefusalReason());
                    item.put("compositeScore", scoreResult(result));
                    item.put("failureType", result.getFailureType());
                    return item;
                })
                .toList();
    }

    private Map<String, Object> buildClosurePlan(AiRagEvalRun run,
                                                 List<AiRagEvalResult> results,
                                                 List<Map<String, Object>> topBadCases) {
        long failedCases = results.stream()
                .filter(result -> StringUtils.hasText(result.getFailureType()) && !"NONE".equalsIgnoreCase(result.getFailureType()))
                .count();
        long weakCitation = results.stream()
                .filter(result -> result.getJudgeCitationSupport() != null && result.getJudgeCitationSupport() < 0.7)
                .count();
        long lowCoverage = results.stream()
                .filter(result -> result.getJudgeCoverage() != null && result.getJudgeCoverage() < 0.7)
                .count();
        boolean baselineReady = StringUtils.hasText(run.getBaselineRunId());
        boolean releaseBlocked = !baselineReady || failedCases > 0 || weakCitation > 0 || lowCoverage > 0;
        List<String> candidateCaseIds = topBadCases.stream()
                .filter(this::shouldConvertTopBadCase)
                .map(item -> String.valueOf(item.get("caseId")))
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();

        List<String> nextActions = new ArrayList<>();
        if (!baselineReady) {
            nextActions.add("run baseline comparison before prompt/config release");
        }
        if (!candidateCaseIds.isEmpty()) {
            nextActions.add("convert low-score top bad cases into golden eval cases");
        }
        if (weakCitation > 0) {
            nextActions.add("tune citation support threshold, prompt citation rules, or retrieval evidence gate");
        }
        if (lowCoverage > 0) {
            nextActions.add("review retrieval recall, query rewrite, and document coverage for low-coverage cases");
        }
        if (nextActions.isEmpty()) {
            nextActions.add("approve release with baseline snapshot and rollback record");
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("baselineReady", baselineReady);
        plan.put("baselineRunId", run.getBaselineRunId());
        plan.put("releaseBlocked", releaseBlocked);
        plan.put("failedCases", failedCases);
        plan.put("weakCitationCases", weakCitation);
        plan.put("lowCoverageCases", lowCoverage);
        plan.put("candidateEvalCaseIds", candidateCaseIds);
        plan.put("nextActions", nextActions);
        plan.put("workflow", List.of(
                "online bad case",
                "human review",
                "convert to eval",
                "baseline comparison",
                "prompt/config release"));
        return plan;
    }

    private boolean shouldConvertTopBadCase(Map<String, Object> item) {
        Object score = item.get("compositeScore");
        if (score instanceof Number number && number.doubleValue() < 0.75) {
            return true;
        }
        Object citation = item.get("judgeCitationSupport");
        if (citation instanceof Number number && number.doubleValue() < 0.7) {
            return true;
        }
        Object coverage = item.get("judgeCoverage");
        if (coverage instanceof Number number && number.doubleValue() < 0.7) {
            return true;
        }
        Object failureType = item.get("failureType");
        return failureType != null
                && StringUtils.hasText(String.valueOf(failureType))
                && !"NONE".equalsIgnoreCase(String.valueOf(failureType));
    }

    private Map<String, Object> buildStructuredJudgeSummary(List<AiRagEvalResult> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("avgRelevance", average(results.stream().map(AiRagEvalResult::getJudgeRelevance).toList()));
        summary.put("avgCoverage", average(results.stream().map(AiRagEvalResult::getJudgeCoverage).toList()));
        summary.put("avgContradiction", average(results.stream().map(AiRagEvalResult::getJudgeContradiction).toList()));
        summary.put("avgCitationSupport", average(results.stream().map(AiRagEvalResult::getJudgeCitationSupport).toList()));
        summary.put("avgAnswerability", average(results.stream().map(AiRagEvalResult::getJudgeAnswerability).toList()));
        summary.put("lowCoverageCount", results.stream()
                .filter(result -> result.getJudgeCoverage() != null && result.getJudgeCoverage() < 0.7)
                .count());
        summary.put("contradictionRiskCount", results.stream()
                .filter(result -> result.getJudgeContradiction() != null && result.getJudgeContradiction() > 0.2)
                .count());
        summary.put("weakCitationCount", results.stream()
                .filter(result -> result.getJudgeCitationSupport() != null && result.getJudgeCitationSupport() < 0.7)
                .count());
        summary.put("unanswerableCount", results.stream()
                .filter(result -> result.getJudgeAnswerability() != null && result.getJudgeAnswerability() < 0.5)
                .count());
        return summary;
    }

    private Double average(List<Double> values) {
        List<Double> available = values.stream()
                .filter(value -> value != null && !value.isNaN() && !value.isInfinite())
                .toList();
        if (available.isEmpty()) {
            return null;
        }
        return available.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double scoreResult(AiRagEvalResult result) {
        List<Double> metrics = new ArrayList<>();
        if (result.getRecallAt5() != null) {
            metrics.add(result.getRecallAt5());
        }
        if (result.getFaithfulnessScore() != null) {
            metrics.add(result.getFaithfulnessScore());
        }
        if (result.getAnswerCorrectnessScore() != null) {
            metrics.add(result.getAnswerCorrectnessScore());
        }
        if (metrics.isEmpty()) {
            return 0.0;
        }
        return metrics.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private enum BreakdownField {
        CATEGORY,
        DIFFICULTY,
        CASE_TYPE
    }
}
