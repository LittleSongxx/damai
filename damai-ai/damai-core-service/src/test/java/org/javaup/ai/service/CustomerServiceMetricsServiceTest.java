package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.entity.AiCustomerServiceMetricEvent;
import org.javaup.ai.mapper.AiCustomerServiceMetricEventMapper;
import org.javaup.ai.mapper.EscalationTicketMapper;
import org.javaup.ai.vo.CustomerServiceDashboardVo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerServiceMetricsServiceTest {

    @Test
    void recordShouldPersistMetricEvent() {
        AiCustomerServiceMetricEventMapper metricMapper = mock(AiCustomerServiceMetricEventMapper.class);
        EscalationTicketMapper ticketMapper = mock(EscalationTicketMapper.class);
        CustomerServiceMetricsService service = new CustomerServiceMetricsService(metricMapper, ticketMapper);

        service.record("run-1", "chat-1", 7L, CustomerServiceMetricsService.QUICK_ANSWER_HIT,
                1D, 120L, Map.of("question", "退票规则"));

        verify(metricMapper).insert(any(AiCustomerServiceMetricEvent.class));
    }

    @Test
    void dashboardShouldAggregateCoreCustomerServiceMetrics() {
        AiCustomerServiceMetricEventMapper metricMapper = mock(AiCustomerServiceMetricEventMapper.class);
        EscalationTicketMapper ticketMapper = mock(EscalationTicketMapper.class);
        when(metricMapper.selectList(any())).thenReturn(List.of(
                event(CustomerServiceMetricsService.QUICK_ANSWER_HIT, 1D, 100L, Map.of("question", "退票规则", "intentCode", "REFUND_RULE")),
                event(CustomerServiceMetricsService.QUICK_ANSWER_MISS, 1D, 400L, Map.of("question", "复杂售后", "intentCode", "ORDER_AFTERSALE")),
                event(CustomerServiceMetricsService.CACHE_HIT, 1D, 90L, Map.of("question", "退票规则")),
                event(CustomerServiceMetricsService.NEGATIVE_SENTIMENT, 1D, null, Map.of("question", "投诉")),
                event(CustomerServiceMetricsService.SATISFACTION, 1D, null, Map.of("question", "退票规则"))
        ));
        when(ticketMapper.selectList(any())).thenReturn(List.of());
        CustomerServiceMetricsService service = new CustomerServiceMetricsService(metricMapper, ticketMapper);

        CustomerServiceDashboardVo dashboard = service.dashboard();

        assertEquals(5L, dashboard.getTotalEvents());
        assertEquals(1L, dashboard.getQuickAnswerHits());
        assertEquals(1L, dashboard.getQuickAnswerMisses());
        assertEquals(0.5D, dashboard.getQuickAnswerHitRate());
        assertEquals(1D, dashboard.getSatisfactionRate());
        assertTrue(String.valueOf(dashboard.getTopQuestions()).contains("退票规则"));
    }

    private AiCustomerServiceMetricEvent event(String metricType,
                                               Double value,
                                               Long latency,
                                               Map<String, Object> dimensions) {
        AiCustomerServiceMetricEvent event = new AiCustomerServiceMetricEvent();
        event.setMetricType(metricType);
        event.setMetricValue(value);
        event.setLatencyMs(latency);
        event.setDimensionsJson(JSON.toJSONString(dimensions));
        event.setStatus(1);
        return event;
    }
}
