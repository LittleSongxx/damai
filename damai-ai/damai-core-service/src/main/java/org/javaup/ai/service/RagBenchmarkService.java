package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.RetrievalStrategyPolicy;
import org.javaup.ai.rag.RetrievalStrategyProfile;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RagBenchmarkService {

    private static final List<RetrievalStrategyProfile> DEFAULT_PROFILES = List.of(
            RetrievalStrategyProfile.STANDARD_HYBRID,
            RetrievalStrategyProfile.ENHANCED_RECOVERY
    );

    private final AiRagEvalCaseMapper caseMapper;
    private final RagRetrievalFacade retrievalFacade;
    private final RetrievalStrategyPolicy strategyPolicy;

    public Map<String, Object> runBenchmark(Map<String, Object> request) {
        int limit = intValue(request, "limit", 20);
        String datasetId = textValue(request, "datasetId", "default-golden");
        String datasetVersion = textValue(request, "datasetVersion", "");
        List<RetrievalStrategyProfile> profiles = parseProfiles(request == null ? null : request.get("profiles"));
        List<AiRagEvalCase> cases = loadCases(datasetId, datasetVersion, limit);

        List<Map<String, Object>> profileReports = new ArrayList<>();
        for (RetrievalStrategyProfile profile : profiles) {
            profileReports.add(runProfile(profile, cases));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("benchmarkRunId", "rag-bench-" + System.currentTimeMillis());
        result.put("startedAt", Instant.now().toString());
        result.put("datasetId", datasetId);
        result.put("datasetVersion", datasetVersion);
        result.put("caseCount", cases.size());
        result.put("profiles", profileReports);
        result.put("qualityGate", buildQualityGate(profileReports));
        result.put("nextActions", buildNextActions(profileReports));
        return result;
    }

    private Map<String, Object> runProfile(RetrievalStrategyProfile profile, List<AiRagEvalCase> cases) {
        List<Long> latencies = new ArrayList<>();
        List<Map<String, Object>> caseResults = new ArrayList<>();
        double recallSum = 0D;
        double hitRateSum = 0D;
        double mrrSum = 0D;
        double citationCoverageSum = 0D;
        int timeoutCount = 0;
        int handoffCount = 0;

        for (AiRagEvalCase evalCase : cases) {
            RetrievalStrategy strategy = strategyFor(profile, evalCase.getQuestion());
            long start = System.currentTimeMillis();
            RagSearchResultVo result = retrievalFacade.retrieve(
                    evalCase.getQuestion(), strategy, KnowledgeRetrievalFilter.empty());
            long latency = System.currentTimeMillis() - start;
            latencies.add(latency);

            List<String> expected = parseExpectedChunks(evalCase.getExpectedChunks());
            List<String> actual = result.getSources() == null ? List.of() : result.getSources().stream()
                    .map(RagSourceVo::getChunkId)
                    .filter(StringUtils::hasText)
                    .toList();
            double recall = recall(expected, actual);
            double hitRate = hitRate(expected, actual);
            double mrr = mrr(expected, actual);
            double citationCoverage = actual.isEmpty() ? 0D : Math.min(1D, actual.size() / 3D);
            boolean timedOut = strategy.latencyBudgetMs() > 0 && latency > strategy.latencyBudgetMs();
            boolean handoff = result.getSources() == null || result.getSources().isEmpty()
                    || strategy.profile() == RetrievalStrategyProfile.HANDOFF_OR_CLARIFY;

            recallSum += recall;
            hitRateSum += hitRate;
            mrrSum += mrr;
            citationCoverageSum += citationCoverage;
            if (timedOut) timeoutCount++;
            if (handoff) handoffCount++;

            Map<String, Object> caseReport = new LinkedHashMap<>();
            caseReport.put("caseId", evalCase.getCaseId());
            caseReport.put("question", evalCase.getQuestion());
            caseReport.put("category", evalCase.getCategory());
            caseReport.put("strategyProfile", profile.name());
            caseReport.put("latencyMs", latency);
            caseReport.put("latencyBudgetMs", strategy.latencyBudgetMs());
            caseReport.put("recallAtK", recall);
            caseReport.put("hitRate", hitRate);
            caseReport.put("mrr", mrr);
            caseReport.put("citationCoverage", citationCoverage);
            caseReport.put("timeout", timedOut);
            caseReport.put("handoffSuggested", handoff);
            caseReport.put("retrievedChunks", actual);
            caseReport.put("metadata", result.getMetadata());
            caseResults.add(caseReport);
        }

        int total = Math.max(1, cases.size());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("profile", profile.name());
        report.put("caseCount", cases.size());
        report.put("p50LatencyMs", percentile(latencies, 0.50));
        report.put("p90LatencyMs", percentile(latencies, 0.90));
        report.put("p95LatencyMs", percentile(latencies, 0.95));
        report.put("p99LatencyMs", percentile(latencies, 0.99));
        report.put("avgRecall", round(recallSum / total));
        report.put("avgHitRate", round(hitRateSum / total));
        report.put("avgMrr", round(mrrSum / total));
        report.put("citationCoverage", round(citationCoverageSum / total));
        report.put("timeoutRate", round((double) timeoutCount / total));
        report.put("handoffRate", round((double) handoffCount / total));
        report.put("slowCases", caseResults.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("timeout")))
                .limit(5)
                .toList());
        report.put("failedCases", caseResults.stream()
                .filter(item -> number(item.get("hitRate")) <= 0D)
                .limit(5)
                .toList());
        report.put("cases", caseResults);
        return report;
    }

    private RetrievalStrategy strategyFor(RetrievalStrategyProfile profile, String query) {
        boolean highRisk = strategyPolicy.isHighRisk(query);
        return switch (profile) {
            case FAST_EXACT -> RetrievalStrategy.fastExact(4, "benchmark fast exact");
            case STANDARD_HYBRID -> RetrievalStrategy.standardHybrid(10, true, highRisk, "benchmark standard hybrid");
            case ENHANCED_RECOVERY -> RetrievalStrategy.enhancedRecovery(
                    12, highRisk, strategyPolicy.shouldUseHyde(query, ""), "benchmark enhanced recovery");
            case HANDOFF_OR_CLARIFY -> RetrievalStrategy.handoffOrClarify(highRisk, "benchmark handoff profile");
        };
    }

    private List<AiRagEvalCase> loadCases(String datasetId, String datasetVersion, int limit) {
        LambdaQueryWrapper<AiRagEvalCase> wrapper = new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getStatus, 1);
        if (StringUtils.hasText(datasetId)) {
            wrapper.eq(AiRagEvalCase::getDatasetId, datasetId);
        }
        if (StringUtils.hasText(datasetVersion)) {
            wrapper.eq(AiRagEvalCase::getDatasetVersion, datasetVersion);
        }
        wrapper.orderByDesc(AiRagEvalCase::getCreateTime).last("limit " + Math.max(1, limit));
        List<AiRagEvalCase> cases = caseMapper.selectList(wrapper);
        if (cases != null && !cases.isEmpty()) {
            return cases;
        }
        return fallbackCases(limit);
    }

    private List<AiRagEvalCase> fallbackCases(int limit) {
        List<String> questions = List.of(
                "演唱会门票可以退吗", "退款多久到账", "实名观演人填错了怎么办", "儿童入场需要证件吗", "电子票二维码打不开怎么办",
                "支付成功但订单没出来怎么办", "重复扣款怎么处理", "演出取消会自动退款吗", "纸质票快递没收到怎么办", "现场取票需要带什么",
                "VIP票能不能转赠", "开演前还能退票吗", "入场安检不能带什么", "抢票排队失败会扣款吗", "订单支付超时后还能恢复吗",
                "发票怎么开", "优惠券退票后会退回吗", "改签支持吗", "座位可以换吗", "非官方渠道买票有风险吗",
                "身份证丢了能入场吗", "二维码被别人截图了怎么办", "候补成功后怎么付款", "票档售罄还能买到吗", "连座怎么选"
        );
        List<AiRagEvalCase> cases = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, questions.size()); i++) {
            AiRagEvalCase evalCase = new AiRagEvalCase();
            evalCase.setCaseId("fallback-" + (i + 1));
            evalCase.setQuestion(questions.get(i));
            evalCase.setCategory(categoryOf(questions.get(i)));
            evalCase.setDifficulty("medium");
            evalCase.setDatasetId("fallback-customer-service");
            evalCase.setDatasetVersion("v1");
            evalCase.setExpectedChunks("");
            cases.add(evalCase);
        }
        return cases;
    }

    private String categoryOf(String question) {
        if (question.contains("退") || question.contains("退款")) return "退票退款";
        if (question.contains("实名") || question.contains("身份证") || question.contains("入场")) return "实名入场";
        if (question.contains("支付") || question.contains("扣款") || question.contains("订单")) return "订单售后";
        if (question.contains("票") || question.contains("座位")) return "购票下单";
        return "客服规则";
    }

    private List<RetrievalStrategyProfile> parseProfiles(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(value -> {
                        try {
                            return RetrievalStrategyProfile.valueOf(value.toUpperCase());
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return DEFAULT_PROFILES;
    }

    private List<String> parseExpectedChunks(String expectedChunks) {
        if (!StringUtils.hasText(expectedChunks)) return List.of();
        String normalized = expectedChunks.replace("[", "").replace("]", "").replace("\"", "");
        Set<String> ids = new LinkedHashSet<>();
        for (String part : normalized.split("[,;\\s]+")) {
            if (StringUtils.hasText(part)) ids.add(part.trim());
        }
        return new ArrayList<>(ids);
    }

    private double recall(List<String> expected, List<String> actual) {
        if (expected.isEmpty()) return actual.isEmpty() ? 0D : 1D;
        long hits = expected.stream().filter(actual::contains).count();
        return round((double) hits / expected.size());
    }

    private double hitRate(List<String> expected, List<String> actual) {
        if (expected.isEmpty()) return actual.isEmpty() ? 0D : 1D;
        return expected.stream().anyMatch(actual::contains) ? 1D : 0D;
    }

    private double mrr(List<String> expected, List<String> actual) {
        if (expected.isEmpty()) return actual.isEmpty() ? 0D : 1D;
        for (int i = 0; i < actual.size(); i++) {
            if (expected.contains(actual.get(i))) {
                return round(1D / (i + 1));
            }
        }
        return 0D;
    }

    private long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private Map<String, Object> buildQualityGate(List<Map<String, Object>> profileReports) {
        Map<String, Object> gate = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Map<String, Object> report : profileReports) {
            double timeoutRate = number(report.get("timeoutRate"));
            double citationCoverage = number(report.get("citationCoverage"));
            if (timeoutRate > 0.20D) {
                failures.add(Map.of("profile", report.get("profile"), "gate", "timeout_rate", "value", timeoutRate));
            }
            if (citationCoverage < 0.60D) {
                failures.add(Map.of("profile", report.get("profile"), "gate", "citation_coverage", "value", citationCoverage));
            }
        }
        gate.put("status", failures.isEmpty() ? "PASS" : "REVIEW");
        gate.put("failures", failures);
        gate.put("rules", List.of(
                "timeoutRate <= 20%",
                "citationCoverage >= 60%",
                "high-risk unsupported answer rate must be handled by evaluation suite"
        ));
        return gate;
    }

    private List<String> buildNextActions(List<Map<String, Object>> profileReports) {
        List<String> actions = new ArrayList<>();
        for (Map<String, Object> report : profileReports) {
            if (number(report.get("timeoutRate")) > 0.20D) {
                actions.add(report.get("profile") + ": reduce rerank/HyDE usage or lower topK for latency-sensitive traffic");
            }
            if (number(report.get("avgHitRate")) < 0.60D) {
                actions.add(report.get("profile") + ": add/repair golden evidence chunks and review domain expansion");
            }
        }
        if (actions.isEmpty()) {
            actions.add("Benchmark passed current gates; compare against previous baseline before release");
        }
        return actions.stream().limit(4).toList();
    }

    private String textValue(Map<String, Object> request, String key, String fallback) {
        Object value = request == null ? null : request.get(key);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private int intValue(Map<String, Object> request, String key, int fallback) {
        Object value = request == null ? null : request.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }
}
