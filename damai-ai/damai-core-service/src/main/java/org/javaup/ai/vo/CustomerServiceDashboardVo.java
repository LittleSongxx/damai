package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class CustomerServiceDashboardVo {

    private Long totalEvents;

    private Long quickAnswerHits;

    private Long quickAnswerMisses;

    private Double quickAnswerHitRate;

    private Long cacheHits;

    private Double cacheHitRate;

    private Long ragHits;

    private Double ragHitRate;

    private Long escalations;

    private Double escalationRate;

    private Long negativeSentiments;

    private Double negativeSentimentRate;

    private Double avgFirstResponseLatencyMs;

    private Double avgCompleteAnswerLatencyMs;

    private Double satisfactionRate;

    private List<Map<String, Object>> topQuestions;

    private List<Map<String, Object>> unresolvedCases;
}
