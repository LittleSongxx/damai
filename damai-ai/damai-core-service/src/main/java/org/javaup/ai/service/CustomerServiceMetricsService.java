package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiCustomerServiceMetricEvent;
import org.javaup.ai.entity.EscalationTicket;
import org.javaup.ai.mapper.AiCustomerServiceMetricEventMapper;
import org.javaup.ai.mapper.EscalationTicketMapper;
import org.javaup.ai.vo.CustomerServiceDashboardVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerServiceMetricsService {

    public static final String QUICK_ANSWER_HIT = "quick_answer_hit";
    public static final String QUICK_ANSWER_MISS = "quick_answer_miss";
    public static final String CACHE_HIT = "cache_hit";
    public static final String RAG_HIT = "rag_hit";
    public static final String ESCALATION_CREATED = "escalation_created";
    public static final String NEGATIVE_SENTIMENT = "negative_sentiment";
    public static final String FIRST_RESPONSE_LATENCY = "first_response_latency";
    public static final String COMPLETE_ANSWER_LATENCY = "complete_answer_latency";
    public static final String SATISFACTION = "satisfaction";

    private final AiCustomerServiceMetricEventMapper metricEventMapper;
    private final EscalationTicketMapper escalationTicketMapper;

    public void record(String runId,
                       String conversationId,
                       Long userId,
                       String metricType,
                       double metricValue,
                       Long latencyMs,
                       Map<String, Object> dimensions) {
        try {
            AiCustomerServiceMetricEvent event = new AiCustomerServiceMetricEvent();
            event.setMetricId(UUID.randomUUID().toString().replace("-", ""));
            event.setRunId(runId);
            event.setConversationId(conversationId);
            event.setUserId(userId);
            event.setMetricType(metricType);
            event.setMetricValue(metricValue);
            event.setLatencyMs(latencyMs);
            event.setDimensionsJson(dimensions == null || dimensions.isEmpty() ? null : JSON.toJSONString(dimensions));
            event.setCreateTime(new Date());
            event.setEditTime(new Date());
            event.setStatus(1);
            metricEventMapper.insert(event);
        } catch (Exception ignored) {
            // 指标采集不能影响客服主链路。
        }
    }

    public CustomerServiceDashboardVo dashboard() {
        List<AiCustomerServiceMetricEvent> events = recentMetricEvents(1000);
        long total = events.size();
        long quickHit = count(events, QUICK_ANSWER_HIT);
        long quickMiss = count(events, QUICK_ANSWER_MISS);
        long cacheHit = count(events, CACHE_HIT);
        long ragHit = count(events, RAG_HIT);
        long escalation = count(events, ESCALATION_CREATED);
        long negative = count(events, NEGATIVE_SENTIMENT);
        double satisfactionRate = satisfactionRate(events);
        return CustomerServiceDashboardVo.builder()
                .totalEvents(total)
                .quickAnswerHits(quickHit)
                .quickAnswerMisses(quickMiss)
                .quickAnswerHitRate(rate(quickHit, quickHit + quickMiss))
                .cacheHits(cacheHit)
                .cacheHitRate(rate(cacheHit, total))
                .ragHits(ragHit)
                .ragHitRate(rate(ragHit, total))
                .escalations(escalation)
                .escalationRate(rate(escalation, total))
                .negativeSentiments(negative)
                .negativeSentimentRate(rate(negative, total))
                .avgFirstResponseLatencyMs(avgLatency(events, FIRST_RESPONSE_LATENCY))
                .avgCompleteAnswerLatencyMs(avgLatency(events, COMPLETE_ANSWER_LATENCY))
                .satisfactionRate(satisfactionRate)
                .topQuestions(topQuestions())
                .unresolvedCases(unresolvedCases())
                .build();
    }

    public List<Map<String, Object>> topQuestions() {
        List<AiCustomerServiceMetricEvent> events = recentMetricEvents(1000);
        Map<String, List<AiCustomerServiceMetricEvent>> grouped = events.stream()
                .filter(event -> QUICK_ANSWER_HIT.equals(event.getMetricType()) || QUICK_ANSWER_MISS.equals(event.getMetricType()))
                .collect(Collectors.groupingBy(event -> dimension(event, "question")));
        return grouped.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()))
                .map(entry -> {
                    long hits = entry.getValue().stream().filter(event -> QUICK_ANSWER_HIT.equals(event.getMetricType())).count();
                    long misses = entry.getValue().stream().filter(event -> QUICK_ANSWER_MISS.equals(event.getMetricType())).count();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("question", entry.getKey());
                    item.put("count", entry.getValue().size());
                    item.put("hits", hits);
                    item.put("misses", misses);
                    item.put("hitRate", rate(hits, hits + misses));
                    item.put("intentCode", dimension(entry.getValue().get(0), "intentCode"));
                    return item;
                })
                .sorted((left, right) -> Long.compare(Number.class.cast(right.get("count")).longValue(),
                        Number.class.cast(left.get("count")).longValue()))
                .limit(10)
                .toList();
    }

    public List<Map<String, Object>> unresolvedCases() {
        List<EscalationTicket> tickets;
        try {
            tickets = escalationTicketMapper.selectList(
                    Wrappers.lambdaQuery(EscalationTicket.class)
                            .in(EscalationTicket::getTicketStatus, List.of("OPEN", "ASSIGNED", "IN_PROGRESS"))
                            .eq(EscalationTicket::getStatus, 1)
                            .orderByDesc(EscalationTicket::getCreateTime)
                            .last("limit 20"));
        } catch (Exception ignored) {
            tickets = List.of();
        }
        return tickets.stream().map(ticket -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ticketId", ticket.getTicketId());
            item.put("priority", ticket.getPriority());
            item.put("sentiment", ticket.getSentiment());
            item.put("intentCode", ticket.getIntentCode());
            item.put("dialogueSummary", ticket.getDialogueSummary());
            item.put("suggestedReply", ticket.getSuggestedReply());
            item.put("createTime", ticket.getCreateTime());
            return item;
        }).toList();
    }

    public Map<String, Object> qualitySnapshot() {
        CustomerServiceDashboardVo dashboard = dashboard();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalEvents", dashboard.getTotalEvents());
        snapshot.put("quickAnswerHitRate", dashboard.getQuickAnswerHitRate());
        snapshot.put("cacheHitRate", dashboard.getCacheHitRate());
        snapshot.put("escalationRate", dashboard.getEscalationRate());
        snapshot.put("negativeSentimentRate", dashboard.getNegativeSentimentRate());
        snapshot.put("satisfactionRate", dashboard.getSatisfactionRate());
        snapshot.put("avgFirstResponseLatencyMs", dashboard.getAvgFirstResponseLatencyMs());
        return snapshot;
    }

    private List<AiCustomerServiceMetricEvent> recentMetricEvents(int limit) {
        try {
            return metricEventMapper.selectList(Wrappers.lambdaQuery(AiCustomerServiceMetricEvent.class)
                    .eq(AiCustomerServiceMetricEvent::getStatus, 1)
                    .orderByDesc(AiCustomerServiceMetricEvent::getCreateTime)
                    .last("limit " + limit));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private long count(List<AiCustomerServiceMetricEvent> events, String metricType) {
        return events.stream().filter(event -> metricType.equals(event.getMetricType())).count();
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return Math.round((numerator * 1D / denominator) * 10000D) / 10000D;
    }

    private Double avgLatency(List<AiCustomerServiceMetricEvent> events, String metricType) {
        return events.stream()
                .filter(event -> metricType.equals(event.getMetricType()))
                .filter(event -> event.getLatencyMs() != null)
                .mapToLong(AiCustomerServiceMetricEvent::getLatencyMs)
                .average()
                .stream()
                .findFirst()
                .orElse(0D);
    }

    private double satisfactionRate(List<AiCustomerServiceMetricEvent> events) {
        List<AiCustomerServiceMetricEvent> satisfactionEvents = events.stream()
                .filter(event -> SATISFACTION.equals(event.getMetricType()))
                .toList();
        long satisfied = satisfactionEvents.stream()
                .filter(event -> event.getMetricValue() != null && event.getMetricValue() > 0D)
                .count();
        return rate(satisfied, satisfactionEvents.size());
    }

    private String dimension(AiCustomerServiceMetricEvent event, String key) {
        if (event == null || !StringUtils.hasText(event.getDimensionsJson())) {
            return "";
        }
        try {
            JSONObject json = JSON.parseObject(event.getDimensionsJson());
            Object value = json == null ? null : json.get(key);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }
}
