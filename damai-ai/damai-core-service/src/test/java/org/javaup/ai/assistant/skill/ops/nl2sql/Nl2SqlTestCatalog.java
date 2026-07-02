package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.service.Nl2SqlSemanticCatalogService;

import java.util.ArrayList;
import java.util.List;

public final class Nl2SqlTestCatalog {

    private Nl2SqlTestCatalog() {
    }

    public static Nl2SqlSemanticCatalogService.CatalogSnapshot snapshot() {
        return new Nl2SqlSemanticCatalogService.CatalogSnapshot(
                "test-metrics",
                5,
                List.of(
                        table("v_order_daily_summary", "订单每日汇总视图，用于观察订单量、支付成功率、GMV、退款和失败订单趋势",
                                List.of("订单量", "支付成功率", "gmv", "退款", "失败订单"),
                                List.of(
                                        column("stat_date", "DATE", "统计日期"),
                                        column("order_count", "BIGINT", "订单总数"),
                                        column("paid_order_count", "BIGINT", "支付成功订单数"),
                                        column("pay_success_rate", "DECIMAL", "支付成功率"),
                                        column("gmv_amount", "DECIMAL", "支付 GMV 金额"),
                                        column("refund_order_count", "BIGINT", "退款订单数"),
                                        column("refund_amount", "DECIMAL", "退款金额"),
                                        column("failed_order_count", "BIGINT", "失败订单数"))),
                        table("v_program_sales", "节目销售汇总视图", List.of("节目销量", "热销节目"),
                                List.of(column("program_name", "VARCHAR", "节目名称"),
                                        column("show_date", "DATE", "演出日期"))),
                        table("v_order_failure_summary", "订单失败汇总视图", List.of("失败订单", "失败原因"),
                                List.of(column("stat_time", "DATETIME", "统计时间"),
                                        column("program_id", "BIGINT", "节目 ID"),
                                        column("program_name", "VARCHAR", "节目名称"),
                                        column("failure_reason", "VARCHAR", "失败原因"),
                                        column("failure_count", "BIGINT", "失败次数"))),
                        table("v_api_call_stats", "接口调用统计视图，用于分析服务接口调用量、错误率和延迟",
                                List.of("接口调用量", "接口错误", "错误率", "延迟", "p95"),
                                List.of(column("stat_time", "DATETIME", "统计时间"),
                                        column("service_name", "VARCHAR", "服务名"),
                                        column("api_path", "VARCHAR", "接口路径"),
                                        column("method", "VARCHAR", "HTTP 方法"),
                                        column("success_count", "BIGINT", "成功次数"),
                                        column("error_count", "BIGINT", "错误次数"),
                                        column("avg_latency_ms", "DECIMAL", "平均延迟毫秒"),
                                        column("p95_latency_ms", "DECIMAL", "P95 延迟毫秒"),
                                        column("error_rate", "DECIMAL", "错误率"))),
                        table("v_ai_usage_cost", "AI 调用成本视图", List.of("AI 成本", "token", "模型调用"),
                                List.of(column("stat_time", "DATETIME", "统计时间"),
                                        column("model_name", "VARCHAR", "模型名称"),
                                        column("request_type", "VARCHAR", "请求类型"),
                                        column("input_tokens", "BIGINT", "输入 tokens"),
                                        column("output_tokens", "BIGINT", "输出 tokens"),
                                        column("total_tokens", "BIGINT", "总 tokens"),
                                        column("estimated_cost", "DECIMAL", "估算成本")))),
                List.of(term("GMV", "支付成功订单对应的成交金额"),
                        term("支付成功率", "支付成功订单数 / 订单总数")),
                List.of(example("今天订单量和支付成功率怎么样",
                        "select stat_date, order_count, paid_order_count, pay_success_rate from v_order_daily_summary where stat_date = current_date() limit 100")));
    }

    private static Nl2SqlProperties.Table table(String name,
                                                String description,
                                                List<String> aliases,
                                                List<Nl2SqlProperties.Column> columns) {
        Nl2SqlProperties.Table table = new Nl2SqlProperties.Table();
        table.setName(name);
        table.setDescription(description);
        table.setAllowed(true);
        table.setAliases(new ArrayList<>(aliases));
        table.setColumns(new ArrayList<>(columns));
        return table;
    }

    private static Nl2SqlProperties.Column column(String name, String type, String description) {
        Nl2SqlProperties.Column column = new Nl2SqlProperties.Column();
        column.setName(name);
        column.setType(type);
        column.setDescription(description);
        return column;
    }

    private static Nl2SqlProperties.Term term(String name, String description) {
        Nl2SqlProperties.Term term = new Nl2SqlProperties.Term();
        term.setName(name);
        term.setDescription(description);
        return term;
    }

    private static Nl2SqlProperties.Example example(String question, String sql) {
        Nl2SqlProperties.Example example = new Nl2SqlProperties.Example();
        example.setQuestion(question);
        example.setSql(sql);
        return example;
    }
}
