package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagEvalBaselineService {

    private final AiRagEvalRunMapper runMapper;
    private final AiRagEvalResultMapper resultMapper;

    public Map<String, Object> compareRun(String evalRunId, String baselineRunId) {
        AiRagEvalRun current = getRun(evalRunId);
        AiRagEvalRun baseline = getRun(baselineRunId);
        if (current == null || baseline == null) {
            return null;
        }
        List<AiRagEvalResult> currentResults = listResults(evalRunId);
        List<AiRagEvalResult> baselineResults = listResults(baselineRunId);
        Map<String, AiRagEvalResult> baselineByCaseId = baselineResults.stream()
                .filter(item -> StringUtils.hasText(item.getCaseId()))
                .collect(Collectors.toMap(AiRagEvalResult::getCaseId, item -> item, (a, b) -> a, LinkedHashMap::new));

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("evalRunId", evalRunId);
        comparison.put("baselineRunId", baselineRunId);
        comparison.put("currentStatus", current.getRunStatus());
        comparison.put("baselineStatus", baseline.getRunStatus());
        comparison.put("metricDiff", buildMetricDiff(current, baseline));
        comparison.put("caseDiffs", buildCaseDiffs(currentResults, baselineByCaseId));
        return comparison;
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

    private Map<String, Object> buildMetricDiff(AiRagEvalRun current, AiRagEvalRun baseline) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("avgRecall", delta(current.getAvgRecall(), baseline.getAvgRecall()));
        diff.put("avgPrecision", delta(current.getAvgPrecision(), baseline.getAvgPrecision()));
        diff.put("avgHitRate", delta(current.getAvgHitRate(), baseline.getAvgHitRate()));
        diff.put("avgMrr", delta(current.getAvgMrr(), baseline.getAvgMrr()));
        diff.put("avgNdcg", delta(current.getAvgNdcg(), baseline.getAvgNdcg()));
        diff.put("avgFaithfulness", delta(current.getAvgFaithfulness(), baseline.getAvgFaithfulness()));
        diff.put("avgAnswerRelevancy", delta(current.getAvgAnswerRelevancy(), baseline.getAvgAnswerRelevancy()));
        diff.put("avgAnswerCorrectness", delta(current.getAvgAnswerCorrectness(), baseline.getAvgAnswerCorrectness()));
        return diff;
    }

    private Double delta(Double current, Double baseline) {
        if (current == null || baseline == null) {
            return null;
        }
        return current - baseline;
    }

    private Map<String, Object> buildCaseDiffs(List<AiRagEvalResult> currentResults,
                                               Map<String, AiRagEvalResult> baselineByCaseId) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (AiRagEvalResult current : currentResults) {
            AiRagEvalResult baseline = baselineByCaseId.get(current.getCaseId());
            if (baseline == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", current.getCaseId());
            item.put("question", current.getQuestion());
            item.put("recallDelta", delta(current.getRecallAt5(), baseline.getRecallAt5()));
            item.put("faithfulnessDelta", delta(current.getFaithfulnessScore(), baseline.getFaithfulnessScore()));
            item.put("correctnessDelta", delta(current.getAnswerCorrectnessScore(), baseline.getAnswerCorrectnessScore()));
            item.put("scoreDelta", score(current) - score(baseline));
            all.add(item);
        }
        List<Map<String, Object>> degraded = all.stream()
                .sorted(Comparator.comparingDouble(item -> ((Number) item.get("scoreDelta")).doubleValue()))
                .limit(10)
                .toList();
        List<Map<String, Object>> improved = all.stream()
                .sorted((a, b) -> Double.compare(((Number) b.get("scoreDelta")).doubleValue(), ((Number) a.get("scoreDelta")).doubleValue()))
                .limit(10)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("degraded", degraded);
        result.put("improved", improved);
        result.put("count", all.size());
        return result;
    }

    private double score(AiRagEvalResult result) {
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
}
