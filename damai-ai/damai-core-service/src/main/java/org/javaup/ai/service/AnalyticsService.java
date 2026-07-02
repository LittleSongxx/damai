package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiFeedback;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.AiFeedbackMapper;
import org.javaup.ai.mapper.AiRetrievalMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 效果评估分析服务 - 参考 Intercom/Zendesk 的 analytics dashboard。
 * 提供CSAT、解决率、FCR、热门问题、Bad Case等核心指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AiRunMapper runMapper;
    private final AiFeedbackMapper feedbackMapper;
    private final AiRetrievalMapper retrievalMapper;
    private final FaqEntryMapper faqEntryMapper;

    /**
     * 核心指标总览 — 参考 Zendesk Explore dashboard
     */
    public DashboardMetrics getDashboardMetrics(String startDate, String endDate) {
        Date start = parseDate(startDate);
        Date end = parseDate(endDate);

        // 总对话数
        Long totalConversations = runMapper.selectCount(
                Wrappers.lambdaQuery(AiRun.class)
                        .ge(start != null, AiRun::getCreateTime, start)
                        .le(end != null, AiRun::getCreateTime, end));

        // 成功解决数 (RESPONDED 状态)
        Long resolvedRuns = runMapper.selectCount(
                Wrappers.lambdaQuery(AiRun.class)
                        .eq(AiRun::getRunStatus, "RESPONDED")
                        .ge(start != null, AiRun::getCreateTime, start)
                        .le(end != null, AiRun::getCreateTime, end));

        // 升级数 (ACTION_REQUIRED 状态)
        Long escalatedRuns = runMapper.selectCount(
                Wrappers.lambdaQuery(AiRun.class)
                        .eq(AiRun::getRunStatus, "WAITING_ACTION")
                        .ge(start != null, AiRun::getCreateTime, start)
                        .le(end != null, AiRun::getCreateTime, end));

        // 失败数
        Long failedRuns = runMapper.selectCount(
                Wrappers.lambdaQuery(AiRun.class)
                        .eq(AiRun::getRunStatus, "FAILED")
                        .ge(start != null, AiRun::getCreateTime, start)
                        .le(end != null, AiRun::getCreateTime, end));

        // 反馈统计
        Long positiveFeedback = feedbackMapper.selectCount(
                Wrappers.lambdaQuery(AiFeedback.class)
                        .eq(AiFeedback::getRating, "up")
                        .ge(start != null, AiFeedback::getCreateTime, start)
                        .le(end != null, AiFeedback::getCreateTime, end));

        Long negativeFeedback = feedbackMapper.selectCount(
                Wrappers.lambdaQuery(AiFeedback.class)
                        .eq(AiFeedback::getRating, "down")
                        .ge(start != null, AiFeedback::getCreateTime, start)
                        .le(end != null, AiFeedback::getCreateTime, end));

        Long totalFeedback = positiveFeedback + negativeFeedback;

        // 平均检索置信度
        List<AiRetrieval> retrievals = retrievalMapper.selectList(
                Wrappers.lambdaQuery(AiRetrieval.class)
                        .ge(start != null, AiRetrieval::getCreateTime, start)
                        .le(end != null, AiRetrieval::getCreateTime, end));
        Double avgConfidence = retrievals.stream()
                .filter(r -> r.getConfidenceScore() != null)
                .mapToDouble(r -> r.getConfidenceScore().doubleValue())
                .average()
                .orElse(0.0);

        // FAQ命中率
        Long faqTotalHits = faqEntryMapper.selectList(Wrappers.lambdaQuery(FaqEntry.class))
                .stream().mapToLong(f -> f.getHitCount() == null ? 0 : f.getHitCount()).sum();

        double resolutionRate = totalConversations > 0
                ? (double) resolvedRuns / totalConversations * 100 : 0;
        double csat = totalFeedback > 0
                ? (double) positiveFeedback / totalFeedback * 100 : 0;
        double workItemRate = totalConversations > 0
                ? (double) escalatedRuns / totalConversations * 100 : 0;
        double failureRate = totalConversations > 0
                ? (double) failedRuns / totalConversations * 100 : 0;

        return new DashboardMetrics(
                totalConversations, resolvedRuns, escalatedRuns, failedRuns,
                positiveFeedback, negativeFeedback,
                Math.round(resolutionRate * 100.0) / 100.0,
                Math.round(csat * 100.0) / 100.0,
                Math.round(workItemRate * 100.0) / 100.0,
                Math.round(failureRate * 100.0) / 100.0,
                Math.round(avgConfidence * 10000.0) / 10000.0,
                faqTotalHits
        );
    }

    /**
     * 热门问题Top10 - 参考 Intercom 的常见问题分析
     */
    public List<TopQuestion> getTopQuestions(String startDate, String endDate, int limit) {
        // 从FAQ命中统计 + RAG检索词统计
        List<FaqEntry> topFaq = faqEntryMapper.selectList(
                Wrappers.lambdaQuery(FaqEntry.class)
                        .orderByDesc(FaqEntry::getHitCount)
                        .last("limit " + limit));

        return topFaq.stream()
                .map(f -> new TopQuestion(f.getQuestion(), f.getCategory(),
                        f.getHitCount() == null ? 0L : f.getHitCount()))
                .collect(Collectors.toList());
    }

    /**
     * Bad Case列表 - 负面反馈聚类分析
     */
    public List<BadCase> getBadCases(int limit) {
        List<AiFeedback> negativeFeedbacks = feedbackMapper.selectList(
                Wrappers.lambdaQuery(AiFeedback.class)
                        .eq(AiFeedback::getRating, "down")
                        .orderByDesc(AiFeedback::getCreateTime)
                        .last("limit " + limit));

        return negativeFeedbacks.stream()
                .map(f -> {
                    AiRun run = runMapper.selectOne(
                            Wrappers.lambdaQuery(AiRun.class)
                                    .eq(AiRun::getRunId, f.getRunId()));
                    return new BadCase(
                            f.getFeedbackId(),
                            f.getRunId(),
                            run != null ? run.getUserMessage() : "",
                            run != null ? run.getResponseSummary() : "",
                            f.getComment(),
                            f.getCreateTime() != null ? f.getCreateTime().toString() : ""
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * FAQ效能分析
     */
    public FaqPerformance getFaqPerformance() {
        List<FaqEntry> allFaq = faqEntryMapper.selectList(Wrappers.lambdaQuery(FaqEntry.class));
        long totalHits = allFaq.stream().mapToLong(f -> f.getHitCount() == null ? 0 : f.getHitCount()).sum();
        long enabledCount = allFaq.stream().filter(f -> f.getEnabled() != null && f.getEnabled() == 1).count();
        long disabledCount = allFaq.size() - enabledCount;
        long zeroHitCount = allFaq.stream().filter(f -> f.getHitCount() == null || f.getHitCount() == 0).count();

        return new FaqPerformance(allFaq.size(), enabledCount, disabledCount, totalHits, zeroHitCount);
    }

    /**
     * 时间趋势 - 每日对话量与解决率
     */
    public List<DailyTrend> getDailyTrend(int days) {
        List<DailyTrend> trends = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            Date dayStart = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date dayEnd = Date.from(date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());

            Long total = runMapper.selectCount(
                    Wrappers.lambdaQuery(AiRun.class)
                            .ge(AiRun::getCreateTime, dayStart)
                            .lt(AiRun::getCreateTime, dayEnd));

            Long resolved = runMapper.selectCount(
                    Wrappers.lambdaQuery(AiRun.class)
                            .eq(AiRun::getRunStatus, "RESPONDED")
                            .ge(AiRun::getCreateTime, dayStart)
                            .lt(AiRun::getCreateTime, dayEnd));

            double rate = total > 0 ? Math.round((double) resolved / total * 10000.0) / 100.0 : 0;
            trends.add(new DailyTrend(date.toString(), total, resolved, rate));
        }
        return trends;
    }

    /**
     * Skill 路由分布
     */
    public List<RouteDistribution> getRouteDistribution(String startDate, String endDate) {
        Date start = parseDate(startDate);
        Date end = parseDate(endDate);

        List<AiRun> runs = runMapper.selectList(
                Wrappers.lambdaQuery(AiRun.class)
                        .ge(start != null, AiRun::getCreateTime, start)
                        .le(end != null, AiRun::getCreateTime, end));

        Map<String, Long> distribution = runs.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRouteType() == null ? "unknown" : r.getRouteType(),
                        Collectors.counting()));

        return distribution.entrySet().stream()
                .map(e -> new RouteDistribution(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.count, a.count))
                .collect(Collectors.toList());
    }

    private Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Date.from(LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Dashboard DTOs ====================

    public record DashboardMetrics(long totalConversations, long resolvedRuns, long escalatedRuns,
                                    long failedRuns, long positiveFeedback, long negativeFeedback,
                                    double resolutionRate, double csat, double workItemRate,
                                    double failureRate, double avgConfidence, long faqTotalHits) {}

    public record TopQuestion(String question, String category, long hitCount) {}

    public record BadCase(String feedbackId, String runId, String userMessage,
                           String aiResponse, String userComment, String createdAt) {}

    public record FaqPerformance(long totalFaq, long enabledFaq, long disabledFaq,
                                  long totalHits, long zeroHitFaq) {}

    public record DailyTrend(String date, long totalConversations, long resolvedConversations,
                              double resolutionRate) {}

    public record RouteDistribution(String routeType, long count) {}
}
