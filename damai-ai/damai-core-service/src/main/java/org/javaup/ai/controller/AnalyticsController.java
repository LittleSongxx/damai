package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/assistant/admin/customer-service/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    public ApiResponse<AnalyticsService.DashboardMetrics> getDashboard(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(analyticsService.getDashboardMetrics(startDate, endDate));
    }

    @GetMapping("/top-questions")
    public ApiResponse<?> getTopQuestions(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(analyticsService.getTopQuestions(startDate, endDate, limit));
    }

    @GetMapping("/bad-cases")
    public ApiResponse<?> getBadCases(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(analyticsService.getBadCases(limit));
    }

    @GetMapping("/faq-performance")
    public ApiResponse<AnalyticsService.FaqPerformance> getFaqPerformance() {
        return ApiResponse.ok(analyticsService.getFaqPerformance());
    }

    @GetMapping("/daily-trend")
    public ApiResponse<?> getDailyTrend(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(analyticsService.getDailyTrend(days));
    }

    @GetMapping("/route-distribution")
    public ApiResponse<?> getRouteDistribution(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.ok(analyticsService.getRouteDistribution(startDate, endDate));
    }
}
