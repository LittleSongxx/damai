CREATE TABLE IF NOT EXISTS d_ai_ops_event_raw (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    source_service VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NULL,
    span_id VARCHAR(128) NULL,
    user_id BIGINT NULL,
    program_id BIGINT NULL,
    order_number VARCHAR(96) NULL,
    reservation_id VARCHAR(96) NULL,
    amount DECIMAL(18,2) NULL,
    count INT NULL,
    event_status VARCHAR(32) NULL,
    occurred_at DATETIME NOT NULL,
    payload_json JSON NULL,
    operator_id VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_event_raw_event (event_id),
    KEY idx_ai_ops_event_raw_type_time (event_type, occurred_at),
    KEY idx_ai_ops_event_raw_service_time (source_service, occurred_at),
    KEY idx_ai_ops_event_raw_trace (trace_id),
    KEY idx_ai_ops_event_raw_business (program_id, order_number, reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_order_daily (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_date DATE NOT NULL,
    order_count BIGINT NOT NULL DEFAULT 0,
    paid_order_count BIGINT NOT NULL DEFAULT 0,
    pay_success_rate DECIMAL(12,6) NOT NULL DEFAULT 0,
    gmv_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_order_count BIGINT NOT NULL DEFAULT 0,
    refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    failed_order_count BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_metric_order_daily (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_program_sales (
    id BIGINT NOT NULL AUTO_INCREMENT,
    program_id BIGINT NOT NULL,
    program_name VARCHAR(255) NULL,
    category_name VARCHAR(128) NULL,
    city_name VARCHAR(128) NULL,
    show_date DATE NULL,
    order_count BIGINT NOT NULL DEFAULT 0,
    ticket_count BIGINT NOT NULL DEFAULT 0,
    gmv_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    pay_success_rate DECIMAL(12,6) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_metric_program_sales (program_id, show_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_ticket_category_sales (
    id BIGINT NOT NULL AUTO_INCREMENT,
    program_id BIGINT NOT NULL,
    program_name VARCHAR(255) NULL,
    ticket_category_id BIGINT NOT NULL,
    ticket_category_name VARCHAR(128) NULL,
    price DECIMAL(18,2) NULL,
    sale_count BIGINT NOT NULL DEFAULT 0,
    stock_count BIGINT NOT NULL DEFAULT 0,
    remaining_count BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_metric_ticket_category_sales (program_id, ticket_category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_pay_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_time DATETIME NOT NULL,
    pay_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    pay_count BIGINT NOT NULL DEFAULT 0,
    refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    refund_count BIGINT NOT NULL DEFAULT 0,
    refund_rate DECIMAL(12,6) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_ops_metric_pay_refund (stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_order_failure (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_time DATETIME NOT NULL,
    program_id BIGINT NULL,
    program_name VARCHAR(255) NULL,
    failure_reason VARCHAR(255) NOT NULL,
    failure_count BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    KEY idx_ai_ops_metric_order_failure (stat_time, program_id, failure_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_api_call (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_time DATETIME NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    api_path VARCHAR(255) NOT NULL,
    method VARCHAR(16) NOT NULL,
    success_count BIGINT NOT NULL DEFAULT 0,
    error_count BIGINT NOT NULL DEFAULT 0,
    avg_latency_ms DECIMAL(18,2) NOT NULL DEFAULT 0,
    p95_latency_ms DECIMAL(18,2) NOT NULL DEFAULT 0,
    error_rate DECIMAL(12,6) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    KEY idx_ai_ops_metric_api_call (stat_time, service_name, api_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_mq_exception (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_time DATETIME NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    topic VARCHAR(128) NULL,
    consumer_group VARCHAR(128) NULL,
    producer_group VARCHAR(128) NULL,
    exception_count BIGINT NOT NULL DEFAULT 0,
    retry_count BIGINT NOT NULL DEFAULT 0,
    last_error_message VARCHAR(1024) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    KEY idx_ai_ops_metric_mq_exception (stat_time, service_name, topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS d_ai_ops_metric_ai_usage_cost (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stat_time DATETIME NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    request_type VARCHAR(64) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(18,6) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status TINYINT NOT NULL DEFAULT 1,
    ext_json JSON NULL,
    PRIMARY KEY (id),
    KEY idx_ai_ops_metric_ai_usage_cost (stat_time, model_name, request_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE OR REPLACE VIEW v_order_daily_summary AS
SELECT stat_date, order_count, paid_order_count, pay_success_rate, gmv_amount, refund_order_count, refund_amount, failed_order_count
FROM d_ai_ops_metric_order_daily
WHERE status = 1;

CREATE OR REPLACE VIEW v_program_sales AS
SELECT program_id, program_name, category_name, city_name, show_date, order_count, ticket_count, gmv_amount, pay_success_rate
FROM d_ai_ops_metric_program_sales
WHERE status = 1;

CREATE OR REPLACE VIEW v_ticket_category_sales AS
SELECT program_id, program_name, ticket_category_id, ticket_category_name, price, sale_count, stock_count, remaining_count
FROM d_ai_ops_metric_ticket_category_sales
WHERE status = 1;

CREATE OR REPLACE VIEW v_pay_refund_summary AS
SELECT stat_time, pay_amount, pay_count, refund_amount, refund_count, refund_rate
FROM d_ai_ops_metric_pay_refund
WHERE status = 1;

CREATE OR REPLACE VIEW v_order_failure_summary AS
SELECT stat_time, program_id, program_name, failure_reason, failure_count
FROM d_ai_ops_metric_order_failure
WHERE status = 1;

CREATE OR REPLACE VIEW v_api_call_stats AS
SELECT stat_time, service_name, api_path, method, success_count, error_count, avg_latency_ms, p95_latency_ms, error_rate
FROM d_ai_ops_metric_api_call
WHERE status = 1;

CREATE OR REPLACE VIEW v_mq_message_exception AS
SELECT stat_time, service_name, topic, consumer_group, producer_group, exception_count, retry_count, last_error_message
FROM d_ai_ops_metric_mq_exception
WHERE status = 1;

CREATE OR REPLACE VIEW v_ai_usage_cost AS
SELECT stat_time, model_name, request_type, input_tokens, output_tokens, total_tokens, estimated_cost
FROM d_ai_ops_metric_ai_usage_cost
WHERE status = 1;

INSERT INTO d_ai_ops_runbook (runbook_id, service_name, scenario_key, title, recommendation, risk_level, executable, review_required, runbook_status, operator_id)
VALUES
('rb_order_success_rate_drop', 'damai-order-service', 'order_success_rate_drop', '下单成功率下降排查', '检查 reservation confirm、库存扣减、订单创建和 MQ 消费失败率；先核对业务事件和 Trace，不自动回滚。', 'HIGH', 0, 1, 'ACTIVE', 'system'),
('rb_payment_success_rate_drop', 'damai-pay-service', 'payment_success_rate_drop', '支付成功率下降排查', '核对支付回调、渠道响应、退款异常和订单状态同步；只提供建议，不自动执行渠道切换。', 'HIGH', 0, 1, 'ACTIVE', 'system'),
('rb_api_latency_spike', NULL, 'api_latency_spike', '接口延迟升高排查', '对齐 P95、错误日志、慢 Trace 和近期发布/配置变更，确认影响范围后由值班人员处理。', 'MEDIUM', 0, 1, 'ACTIVE', 'system')
ON DUPLICATE KEY UPDATE recommendation = VALUES(recommendation), risk_level = VALUES(risk_level), runbook_status = VALUES(runbook_status);

INSERT INTO d_ai_nl2sql_semantic_catalog
(catalog_id, datasource_key, semantic_type, semantic_key, display_name, dataset_name, table_name, column_name, expression_sql, allowed_view, sensitivity_level, example_sql, version_no, catalog_status, operator_id, ext_json)
VALUES
('cat_view_order_daily', 'damai-ai-metrics', 'VIEW', 'v_order_daily_summary', '订单每日汇总', 'order', 'v_order_daily_summary', NULL, NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('订单量','支付成功率','GMV','退款','失败订单'), 'description', '订单每日汇总视图，用于观察订单量、支付成功率、GMV、退款和失败订单趋势')),
('cat_view_program_sales', 'damai-ai-metrics', 'VIEW', 'v_program_sales', '节目销售汇总', 'program', 'v_program_sales', NULL, NULL, 'v_program_sales', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('节目销量','演唱会销售','城市销售','热销节目'), 'description', '节目销售汇总视图，用于分析演出、城市、日期维度的订单、票数和 GMV')),
('cat_view_ticket_category_sales', 'damai-ai-metrics', 'VIEW', 'v_ticket_category_sales', '票档销售库存', 'ticket', 'v_ticket_category_sales', NULL, NULL, 'v_ticket_category_sales', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('票档库存','余票','库存告急','票档销量'), 'description', '票档销售和库存视图，用于分析票档销售、余量和库存告警')),
('cat_view_pay_refund_summary', 'damai-ai-metrics', 'VIEW', 'v_pay_refund_summary', '支付退款汇总', 'payment', 'v_pay_refund_summary', NULL, NULL, 'v_pay_refund_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('支付','退款','退款率','支付金额'), 'description', '支付退款汇总视图，用于分析支付金额、退款金额、退款率和异常趋势')),
('cat_view_order_failure_summary', 'damai-ai-metrics', 'VIEW', 'v_order_failure_summary', '订单失败汇总', 'order_failure', 'v_order_failure_summary', NULL, NULL, 'v_order_failure_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('失败订单','下单失败','订单异常','失败原因'), 'description', '订单失败汇总视图，用于定位失败订单、失败原因和相关节目')),
('cat_view_api_call_stats', 'damai-ai-metrics', 'VIEW', 'v_api_call_stats', '接口调用统计', 'api', 'v_api_call_stats', NULL, NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('接口调用量','接口错误','错误率','延迟','p95'), 'description', '接口调用统计视图，用于分析服务接口调用量、错误率和延迟')),
('cat_view_mq_message_exception', 'damai-ai-metrics', 'VIEW', 'v_mq_message_exception', 'MQ异常汇总', 'mq', 'v_mq_message_exception', NULL, NULL, 'v_mq_message_exception', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('消息异常','MQ','消费失败','重试'), 'description', '消息异常汇总视图，用于排查 MQ 生产、消费、重试和异常消息')),
('cat_view_ai_usage_cost', 'damai-ai-metrics', 'VIEW', 'v_ai_usage_cost', 'AI调用成本', 'ai_cost', 'v_ai_usage_cost', NULL, NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('aliases', JSON_ARRAY('AI成本','token','模型调用','大模型成本'), 'description', 'AI 调用成本视图，用于分析模型调用量、token 和估算成本')),
('cat_term_gmv', 'damai-ai-metrics', 'TERM', 'gmv', 'GMV', NULL, NULL, NULL, '支付成功订单对应的成交金额', NULL, 'PUBLIC', NULL, 5, 'ACTIVE', 'system', NULL),
('cat_term_pay_success_rate', 'damai-ai-metrics', 'TERM', 'pay_success_rate', '支付成功率', NULL, NULL, NULL, '支付成功订单数 / 订单总数', NULL, 'PUBLIC', NULL, 5, 'ACTIVE', 'system', NULL),
('cat_term_refund_rate', 'damai-ai-metrics', 'TERM', 'refund_rate', '退款率', NULL, NULL, NULL, '退款笔数 / 支付笔数', NULL, 'PUBLIC', NULL, 5, 'ACTIVE', 'system', NULL),
('cat_example_order_today', 'damai-ai-metrics', 'EXAMPLE', 'order_today', '今天订单量和支付成功率怎么样', NULL, NULL, NULL, NULL, NULL, 'PUBLIC', 'select stat_date, order_count, paid_order_count, pay_success_rate from v_order_daily_summary where stat_date = current_date() limit 100', 5, 'ACTIVE', 'system', NULL),
('cat_example_api_error', 'damai-ai-metrics', 'EXAMPLE', 'api_error_top', '哪个接口错误最多', NULL, NULL, NULL, NULL, NULL, 'PUBLIC', 'select service_name, api_path, sum(error_count) as error_count from v_api_call_stats where stat_time >= date_sub(now(), interval 1 hour) group by service_name, api_path order by error_count desc limit 10', 5, 'ACTIVE', 'system', NULL)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), ext_json = VALUES(ext_json), catalog_status = VALUES(catalog_status), version_no = VALUES(version_no);

INSERT INTO d_ai_nl2sql_semantic_catalog
(catalog_id, datasource_key, semantic_type, semantic_key, display_name, dataset_name, table_name, column_name, expression_sql, allowed_view, sensitivity_level, example_sql, version_no, catalog_status, operator_id, ext_json)
VALUES
('cat_field_order_stat_date', 'damai-ai-metrics', 'FIELD', 'v_order_daily_summary.stat_date', '统计日期', 'order', 'v_order_daily_summary', 'stat_date', NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DATE')),
('cat_field_order_order_count', 'damai-ai-metrics', 'FIELD', 'v_order_daily_summary.order_count', '订单总数', 'order', 'v_order_daily_summary', 'order_count', NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_order_paid_order_count', 'damai-ai-metrics', 'FIELD', 'v_order_daily_summary.paid_order_count', '支付成功订单数', 'order', 'v_order_daily_summary', 'paid_order_count', NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_order_pay_success_rate', 'damai-ai-metrics', 'FIELD', 'v_order_daily_summary.pay_success_rate', '支付成功率', 'order', 'v_order_daily_summary', 'pay_success_rate', NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL')),
('cat_field_order_gmv_amount', 'damai-ai-metrics', 'FIELD', 'v_order_daily_summary.gmv_amount', '支付 GMV 金额', 'order', 'v_order_daily_summary', 'gmv_amount', NULL, 'v_order_daily_summary', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL')),
('cat_field_api_stat_time', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.stat_time', '统计时间', 'api', 'v_api_call_stats', 'stat_time', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DATETIME')),
('cat_field_api_service_name', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.service_name', '服务名', 'api', 'v_api_call_stats', 'service_name', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','VARCHAR')),
('cat_field_api_api_path', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.api_path', '接口路径', 'api', 'v_api_call_stats', 'api_path', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','VARCHAR')),
('cat_field_api_method', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.method', 'HTTP 方法', 'api', 'v_api_call_stats', 'method', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','VARCHAR')),
('cat_field_api_success_count', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.success_count', '成功次数', 'api', 'v_api_call_stats', 'success_count', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_api_error_count', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.error_count', '错误次数', 'api', 'v_api_call_stats', 'error_count', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_api_avg_latency_ms', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.avg_latency_ms', '平均延迟毫秒', 'api', 'v_api_call_stats', 'avg_latency_ms', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL')),
('cat_field_api_p95_latency_ms', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.p95_latency_ms', 'P95 延迟毫秒', 'api', 'v_api_call_stats', 'p95_latency_ms', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL')),
('cat_field_api_error_rate', 'damai-ai-metrics', 'FIELD', 'v_api_call_stats.error_rate', '错误率', 'api', 'v_api_call_stats', 'error_rate', NULL, 'v_api_call_stats', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL')),
('cat_field_ai_cost_stat_time', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.stat_time', '统计时间', 'ai_cost', 'v_ai_usage_cost', 'stat_time', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DATETIME')),
('cat_field_ai_cost_model_name', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.model_name', '模型名称', 'ai_cost', 'v_ai_usage_cost', 'model_name', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','VARCHAR')),
('cat_field_ai_cost_request_type', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.request_type', '请求类型', 'ai_cost', 'v_ai_usage_cost', 'request_type', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','VARCHAR')),
('cat_field_ai_cost_input_tokens', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.input_tokens', '输入 tokens', 'ai_cost', 'v_ai_usage_cost', 'input_tokens', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_ai_cost_output_tokens', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.output_tokens', '输出 tokens', 'ai_cost', 'v_ai_usage_cost', 'output_tokens', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_ai_cost_total_tokens', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.total_tokens', '总 tokens', 'ai_cost', 'v_ai_usage_cost', 'total_tokens', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','BIGINT')),
('cat_field_ai_cost_estimated_cost', 'damai-ai-metrics', 'FIELD', 'v_ai_usage_cost.estimated_cost', '估算成本', 'ai_cost', 'v_ai_usage_cost', 'estimated_cost', NULL, 'v_ai_usage_cost', 'PUBLIC', NULL, 5, 'ACTIVE', 'system', JSON_OBJECT('dataType','DECIMAL'))
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), ext_json = VALUES(ext_json), catalog_status = VALUES(catalog_status), version_no = VALUES(version_no);
