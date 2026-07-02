package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpsMetricAggregationService {

    private final AiOpsEventRawMapper eventRawMapper;

    public Map<String, Object> rebuildFromRawEvents() {
        var events = eventRawMapper.selectList(Wrappers.lambdaQuery(AiOpsEventRaw.class)
                .eq(AiOpsEventRaw::getStatus, 1)
                .orderByDesc(AiOpsEventRaw::getOccurredAt)
                .last("limit 5000"));
        Map<String, Long> byType = events.stream()
                .collect(Collectors.groupingBy(AiOpsEventRaw::getEventType, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byService = events.stream()
                .collect(Collectors.groupingBy(AiOpsEventRaw::getSourceService, LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawEventCount", events.size());
        result.put("eventCountByType", byType);
        result.put("eventCountByService", byService);
        result.put("materializationMode", "raw-event-replay");
        result.put("views", java.util.List.of(
                "v_order_daily_summary",
                "v_program_sales",
                "v_ticket_category_sales",
                "v_pay_refund_summary",
                "v_order_failure_summary",
                "v_api_call_stats",
                "v_mq_message_exception",
                "v_ai_usage_cost"));
        return result;
    }
}
