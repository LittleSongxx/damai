package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiOpsEventRaw;
import org.javaup.ai.mapper.AiOpsEventRawMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpsMetricAggregationService {

    private final AiOpsEventRawMapper eventRawMapper;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> rebuildFromRawEvents() {
        var events = eventRawMapper.selectList(Wrappers.lambdaQuery(AiOpsEventRaw.class)
                .eq(AiOpsEventRaw::getStatus, 1)
                .orderByDesc(AiOpsEventRaw::getOccurredAt)
                .last("limit 5000"));
        Map<String, Integer> materializedRows = materializeRawEvents();
        Map<String, Long> byType = events.stream()
                .collect(Collectors.groupingBy(AiOpsEventRaw::getEventType, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byService = events.stream()
                .collect(Collectors.groupingBy(AiOpsEventRaw::getSourceService, LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawEventCount", events.size());
        result.put("eventCountByType", byType);
        result.put("eventCountByService", byService);
        result.put("materializationMode", "raw-event-replay-to-wide-tables");
        result.put("materializedRows", materializedRows);
        result.put("coverageTimeWindow", coverageTimeWindow());
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

    private Map<String, Integer> materializeRawEvents() {
        Map<String, Integer> rows = new LinkedHashMap<>();
        rows.put("orderDaily", rebuildOrderDaily());
        rows.put("programSales", rebuildProgramSales());
        rows.put("ticketCategorySales", rebuildTicketCategorySales());
        rows.put("payRefund", rebuildPayRefund());
        rows.put("orderFailure", rebuildOrderFailure());
        rows.put("apiCall", rebuildApiCall());
        rows.put("mqException", rebuildMqException());
        rows.put("aiUsageCost", rebuildAiUsageCost());
        return rows;
    }

    private int rebuildOrderDaily() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_order_daily");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_order_daily
                (stat_date, order_count, paid_order_count, pay_success_rate, gmv_amount, refund_order_count, refund_amount, failed_order_count)
                SELECT DATE(occurred_at) AS stat_date,
                       SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END) AS order_count,
                       SUM(CASE WHEN event_type IN ('ORDER_PAID','PAYMENT_SUCCESS') OR (event_type='ORDER_CREATED' AND event_status='SUCCESS') THEN 1 ELSE 0 END) AS paid_order_count,
                       CASE WHEN SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END)=0
                            THEN 0
                            ELSE SUM(CASE WHEN event_type IN ('ORDER_PAID','PAYMENT_SUCCESS') OR (event_type='ORDER_CREATED' AND event_status='SUCCESS') THEN 1 ELSE 0 END)
                                 / SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END)
                       END AS pay_success_rate,
                       COALESCE(SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') AND event_status='SUCCESS' THEN amount ELSE 0 END), 0) AS gmv_amount,
                       SUM(CASE WHEN event_type IN ('REFUND_SUCCESS','ORDER_REFUNDED') THEN 1 ELSE 0 END) AS refund_order_count,
                       COALESCE(SUM(CASE WHEN event_type IN ('REFUND_SUCCESS','ORDER_REFUNDED') THEN amount ELSE 0 END), 0) AS refund_amount,
                       SUM(CASE WHEN event_type IN ('ORDER_CREATE_FAILED','ORDER_FAILED','INVENTORY_RESERVE_FAILED') OR event_status='FAILED' THEN 1 ELSE 0 END) AS failed_order_count
                FROM d_ai_ops_event_raw
                WHERE status = 1
                GROUP BY DATE(occurred_at)
                """);
    }

    private int rebuildProgramSales() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_program_sales");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_program_sales
                (program_id, program_name, category_name, city_name, show_date, order_count, ticket_count, gmv_amount, pay_success_rate)
                SELECT program_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.programName')) AS program_name,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.categoryName')) AS category_name,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.cityName')) AS city_name,
                       DATE(occurred_at) AS show_date,
                       SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END) AS order_count,
                       COALESCE(SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN count ELSE 0 END), 0) AS ticket_count,
                       COALESCE(SUM(CASE WHEN event_status='SUCCESS' THEN amount ELSE 0 END), 0) AS gmv_amount,
                       CASE WHEN SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END)=0
                            THEN 0
                            ELSE SUM(CASE WHEN event_status='SUCCESS' THEN 1 ELSE 0 END)
                                 / SUM(CASE WHEN event_type IN ('ORDER_CREATED','ORDER_PAID','PAYMENT_SUCCESS') THEN 1 ELSE 0 END)
                       END AS pay_success_rate
                FROM d_ai_ops_event_raw
                WHERE status = 1 AND program_id IS NOT NULL
                GROUP BY program_id, DATE(occurred_at)
                """);
    }

    private int rebuildTicketCategorySales() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_ticket_category_sales");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_ticket_category_sales
                (program_id, program_name, ticket_category_id, ticket_category_name, price, sale_count, stock_count, remaining_count)
                SELECT program_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.programName')) AS program_name,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.ticketCategoryId')) AS UNSIGNED) AS ticket_category_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.ticketCategoryName')) AS ticket_category_name,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.ticketCategoryPrice')) AS DECIMAL(18,2)) AS price,
                       COALESCE(SUM(CASE WHEN event_type IN ('ORDER_CREATED','AI_RESERVATION_CONFIRMED','INVENTORY_RESERVED') THEN count ELSE 0 END), 0) AS sale_count,
                       COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.stockCount')) AS UNSIGNED)), 0) AS stock_count,
                       COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.remainingCount')) AS UNSIGNED)), 0) AS remaining_count
                FROM d_ai_ops_event_raw
                WHERE status = 1
                  AND program_id IS NOT NULL
                  AND JSON_EXTRACT(payload_json, '$.ticketCategoryId') IS NOT NULL
                GROUP BY program_id, CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.ticketCategoryId')) AS UNSIGNED)
                """);
    }

    private int rebuildPayRefund() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_pay_refund");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_pay_refund
                (stat_time, pay_amount, pay_count, refund_amount, refund_count, refund_rate)
                SELECT TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)) AS stat_time,
                       COALESCE(SUM(CASE WHEN event_type IN ('PAYMENT_SUCCESS','ORDER_PAID','ORDER_CREATED') AND event_status='SUCCESS' THEN amount ELSE 0 END), 0) AS pay_amount,
                       SUM(CASE WHEN event_type IN ('PAYMENT_SUCCESS','ORDER_PAID','ORDER_CREATED') AND event_status='SUCCESS' THEN 1 ELSE 0 END) AS pay_count,
                       COALESCE(SUM(CASE WHEN event_type IN ('REFUND_SUCCESS','ORDER_REFUNDED') THEN amount ELSE 0 END), 0) AS refund_amount,
                       SUM(CASE WHEN event_type IN ('REFUND_SUCCESS','ORDER_REFUNDED') THEN 1 ELSE 0 END) AS refund_count,
                       CASE WHEN SUM(CASE WHEN event_type IN ('PAYMENT_SUCCESS','ORDER_PAID','ORDER_CREATED') AND event_status='SUCCESS' THEN 1 ELSE 0 END)=0
                            THEN 0
                            ELSE SUM(CASE WHEN event_type IN ('REFUND_SUCCESS','ORDER_REFUNDED') THEN 1 ELSE 0 END)
                                 / SUM(CASE WHEN event_type IN ('PAYMENT_SUCCESS','ORDER_PAID','ORDER_CREATED') AND event_status='SUCCESS' THEN 1 ELSE 0 END)
                       END AS refund_rate
                FROM d_ai_ops_event_raw
                WHERE status = 1
                GROUP BY TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0))
                """);
    }

    private int rebuildOrderFailure() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_order_failure");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_order_failure
                (stat_time, program_id, program_name, failure_reason, failure_count)
                SELECT TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)) AS stat_time,
                       program_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.programName')) AS program_name,
                       COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.errorMessage')), ''), event_type) AS failure_reason,
                       COUNT(*) AS failure_count
                FROM d_ai_ops_event_raw
                WHERE status = 1
                  AND (event_status='FAILED' OR event_type LIKE '%FAILED%' OR event_type LIKE '%EXCEPTION%')
                GROUP BY TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)),
                         program_id,
                         COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.errorMessage')), ''), event_type)
                """);
    }

    private int rebuildApiCall() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_api_call");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_api_call
                (stat_time, service_name, api_path, method, success_count, error_count, avg_latency_ms, p95_latency_ms, error_rate)
                SELECT TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)) AS stat_time,
                       source_service,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.route')),
                                JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.path')),
                                JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.apiData.path')),
                                'unknown') AS api_path,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.method')),
                                JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.apiData.method')),
                                'UNKNOWN') AS method,
                       SUM(CASE WHEN event_status IN ('SUCCESS','RECORDED') OR event_status IS NULL THEN 1 ELSE 0 END) AS success_count,
                       SUM(CASE WHEN event_status='FAILED' THEN 1 ELSE 0 END) AS error_count,
                       COALESCE(AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.latencyMs')) AS DECIMAL(18,2))), 0) AS avg_latency_ms,
                       COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.latencyMs')) AS DECIMAL(18,2))), 0) AS p95_latency_ms,
                       CASE WHEN COUNT(*)=0 THEN 0 ELSE SUM(CASE WHEN event_status='FAILED' THEN 1 ELSE 0 END) / COUNT(*) END AS error_rate
                FROM d_ai_ops_event_raw
                WHERE status = 1 AND event_type='API_CALLED'
                GROUP BY TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)),
                         source_service,
                         COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.route')),
                                  JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.path')),
                                  JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.apiData.path')),
                                  'unknown'),
                         COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.method')),
                                  JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.apiData.method')),
                                  'UNKNOWN')
                """);
    }

    private int rebuildMqException() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_mq_exception");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_mq_exception
                (stat_time, service_name, topic, consumer_group, producer_group, exception_count, retry_count, last_error_message)
                SELECT TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)) AS stat_time,
                       source_service,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.topic')),
                                JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.routingKey')),
                                'unknown') AS topic,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.consumerGroup')) AS consumer_group,
                       JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.producerGroup')) AS producer_group,
                       COUNT(*) AS exception_count,
                       COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.retryCount')) AS UNSIGNED)), 0) AS retry_count,
                       MAX(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.errorMessage'))) AS last_error_message
                FROM d_ai_ops_event_raw
                WHERE status = 1 AND event_type='MQ_MESSAGE_EXCEPTION'
                GROUP BY TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)),
                         source_service,
                         COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.topic')),
                                  JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.routingKey')),
                                  'unknown'),
                         JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.consumerGroup')),
                         JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.producerGroup'))
                """);
    }

    private int rebuildAiUsageCost() {
        jdbcTemplate.update("DELETE FROM d_ai_ops_metric_ai_usage_cost");
        return jdbcTemplate.update("""
                INSERT INTO d_ai_ops_metric_ai_usage_cost
                (stat_time, model_name, request_type, input_tokens, output_tokens, total_tokens, estimated_cost)
                SELECT TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)) AS stat_time,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.modelName')), 'unknown') AS model_name,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.requestType')), event_type) AS request_type,
                       COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.inputTokens')) AS UNSIGNED)), 0) AS input_tokens,
                       COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.outputTokens')) AS UNSIGNED)), 0) AS output_tokens,
                       COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.totalTokens')) AS UNSIGNED)), 0) AS total_tokens,
                       COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.estimatedCost')) AS DECIMAL(18,6))), 0) AS estimated_cost
                FROM d_ai_ops_event_raw
                WHERE status = 1 AND event_type IN ('AI_MODEL_USAGE','AI_USAGE_COST','LLM_USAGE')
                GROUP BY TIMESTAMP(DATE(occurred_at), MAKETIME(HOUR(occurred_at), 0, 0)),
                         COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.modelName')), 'unknown'),
                         COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.requestType')), event_type)
                """);
    }

    private Map<String, Object> coverageTimeWindow() {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", jdbcTemplate.queryForObject(
                "SELECT MIN(occurred_at) FROM d_ai_ops_event_raw WHERE status = 1", Object.class));
        window.put("end", jdbcTemplate.queryForObject(
                "SELECT MAX(occurred_at) FROM d_ai_ops_event_raw WHERE status = 1", Object.class));
        return window;
    }
}
