package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.nl2sql")
public class Nl2SqlProperties {

    private boolean enabled = true;

    private int maxRows = 100;

    private int schemaTopK = 5;

    private int queryTimeoutMs = 5000;

    private int repairAttempts = 1;

    private String schemaCollection = "damai_ai_nl2sql_schema";

    private DataSource datasource = new DataSource();

    private List<String> sensitiveColumns = new ArrayList<>(List.of(
            "mobile", "phone", "email", "password", "id_number", "idcard", "identity",
            "salt", "access_token", "refresh_token", "secret", "secret_key", "signsecretkey", "aeskey", "aes_key",
            "private_key", "access_key", "credential"
    ));

    private List<String> blockedFunctions = new ArrayList<>(List.of(
            "sleep", "benchmark", "load_file", "into outfile", "into dumpfile", "uuid_short"
    ));

    private List<Table> tables = defaultTables();

    private List<Term> terms = defaultTerms();

    private List<Example> examples = defaultExamples();

    @Data
    public static class DataSource {

        private String driverClassName = "com.mysql.cj.jdbc.Driver";

        private String url = "";

        private String username = "";

        private String password = "";
    }

    @Data
    public static class Table {

        private String name;

        private String description;

        private boolean allowed = true;

        private List<String> aliases = new ArrayList<>();

        private List<Column> columns = new ArrayList<>();
    }

    @Data
    public static class Column {

        private String name;

        private String type;

        private String description;

        private boolean sensitive = false;
    }

    @Data
    public static class Term {

        private String name;

        private String description;
    }

    @Data
    public static class Example {

        private String question;

        private String sql;
    }

    private static List<Table> defaultTables() {
        return new ArrayList<>(List.of(
                table("v_order_daily_summary", "订单每日汇总视图，用于观察订单量、支付成功率、GMV、退款和失败订单趋势",
                        List.of("订单量", "支付成功率", "gmv", "退款", "失败订单"),
                        List.of(
                                column("stat_date", "DATE", "统计日期"),
                                column("order_count", "BIGINT", "订单总数"),
                                column("paid_order_count", "BIGINT", "支付成功订单数"),
                                column("pay_success_rate", "DECIMAL", "支付成功率，0 到 1"),
                                column("gmv_amount", "DECIMAL", "支付 GMV 金额"),
                                column("refund_order_count", "BIGINT", "退款订单数"),
                                column("refund_amount", "DECIMAL", "退款金额"),
                                column("failed_order_count", "BIGINT", "失败订单数")
                        )),
                table("v_program_sales", "节目销售汇总视图，用于分析演出、城市、日期维度的订单、票数和 GMV",
                        List.of("节目销量", "演唱会销售", "城市销售", "热销节目"),
                        List.of(
                                column("program_id", "BIGINT", "节目 ID"),
                                column("program_name", "VARCHAR", "节目名称"),
                                column("category_name", "VARCHAR", "节目分类"),
                                column("city_name", "VARCHAR", "城市"),
                                column("show_date", "DATE", "演出日期"),
                                column("order_count", "BIGINT", "订单数"),
                                column("ticket_count", "BIGINT", "售出票数"),
                                column("gmv_amount", "DECIMAL", "支付 GMV 金额"),
                                column("pay_success_rate", "DECIMAL", "支付成功率，0 到 1")
                        )),
                table("v_ticket_category_sales", "票档销售和库存视图，用于分析票档销售、余量和库存告警",
                        List.of("票档库存", "余票", "库存告急", "票档销量"),
                        List.of(
                                column("program_id", "BIGINT", "节目 ID"),
                                column("program_name", "VARCHAR", "节目名称"),
                                column("ticket_category_id", "BIGINT", "票档 ID"),
                                column("ticket_category_name", "VARCHAR", "票档名称"),
                                column("price", "DECIMAL", "票档价格"),
                                column("sale_count", "BIGINT", "售出票数"),
                                column("stock_count", "BIGINT", "总库存"),
                                column("remaining_count", "BIGINT", "剩余库存")
                        )),
                table("v_pay_refund_summary", "支付退款汇总视图，用于分析支付金额、退款金额、退款率和异常趋势",
                        List.of("支付", "退款", "退款率", "支付金额"),
                        List.of(
                                column("stat_time", "DATETIME", "统计时间"),
                                column("pay_amount", "DECIMAL", "支付金额"),
                                column("pay_count", "BIGINT", "支付笔数"),
                                column("refund_amount", "DECIMAL", "退款金额"),
                                column("refund_count", "BIGINT", "退款笔数"),
                                column("refund_rate", "DECIMAL", "退款率，0 到 1")
                        )),
                table("v_order_failure_summary", "订单失败汇总视图，用于定位失败订单、失败原因和相关节目",
                        List.of("失败订单", "下单失败", "订单异常", "失败原因"),
                        List.of(
                                column("stat_time", "DATETIME", "统计时间"),
                                column("program_id", "BIGINT", "节目 ID"),
                                column("program_name", "VARCHAR", "节目名称"),
                                column("failure_reason", "VARCHAR", "失败原因"),
                                column("failure_count", "BIGINT", "失败次数")
                        )),
                table("v_api_call_stats", "接口调用统计视图，用于分析服务接口调用量、错误率和延迟",
                        List.of("接口调用量", "接口错误", "错误率", "延迟", "p95"),
                        List.of(
                                column("stat_time", "DATETIME", "统计时间"),
                                column("service_name", "VARCHAR", "服务名"),
                                column("api_path", "VARCHAR", "接口路径"),
                                column("method", "VARCHAR", "HTTP 方法"),
                                column("success_count", "BIGINT", "成功次数"),
                                column("error_count", "BIGINT", "错误次数"),
                                column("avg_latency_ms", "DECIMAL", "平均延迟毫秒"),
                                column("p95_latency_ms", "DECIMAL", "P95 延迟毫秒"),
                                column("error_rate", "DECIMAL", "错误率，0 到 1")
                        )),
                table("v_mq_message_exception", "消息异常汇总视图，用于排查 MQ 生产、消费、重试和异常消息",
                        List.of("消息异常", "MQ", "消费失败", "重试"),
                        List.of(
                                column("stat_time", "DATETIME", "统计时间"),
                                column("service_name", "VARCHAR", "服务名"),
                                column("topic", "VARCHAR", "消息主题"),
                                column("consumer_group", "VARCHAR", "消费者组"),
                                column("producer_group", "VARCHAR", "生产者组"),
                                column("exception_count", "BIGINT", "异常次数"),
                                column("retry_count", "BIGINT", "重试次数"),
                                column("last_error_message", "VARCHAR", "最近错误摘要")
                        )),
                table("v_ai_usage_cost", "AI 调用成本视图，用于分析模型调用量、token 和估算成本",
                        List.of("AI 成本", "token", "模型调用", "大模型成本"),
                        List.of(
                                column("stat_time", "DATETIME", "统计时间"),
                                column("model_name", "VARCHAR", "模型名称"),
                                column("request_type", "VARCHAR", "请求类型"),
                                column("input_tokens", "BIGINT", "输入 tokens"),
                                column("output_tokens", "BIGINT", "输出 tokens"),
                                column("total_tokens", "BIGINT", "总 tokens"),
                                column("estimated_cost", "DECIMAL", "估算成本")
                        ))
        ));
    }

    private static List<Term> defaultTerms() {
        return new ArrayList<>(List.of(
                term("GMV", "支付成功订单对应的成交金额"),
                term("支付成功率", "支付成功订单数 / 订单总数"),
                term("退款率", "退款笔数 / 支付笔数"),
                term("错误率", "错误次数 / 总调用次数"),
                term("库存告急", "剩余库存较低，需要结合 remaining_count 判断")
        ));
    }

    private static List<Example> defaultExamples() {
        return new ArrayList<>(List.of(
                example("今天订单量和支付成功率怎么样",
                        "select stat_date, order_count, paid_order_count, pay_success_rate from v_order_daily_summary where stat_date = current_date() limit 100"),
                example("最近一小时失败订单最多的节目是哪些",
                        "select program_name, sum(failure_count) as failure_count from v_order_failure_summary where stat_time >= date_sub(now(), interval 1 hour) group by program_name order by failure_count desc limit 10"),
                example("哪个接口错误最多",
                        "select service_name, api_path, sum(error_count) as error_count from v_api_call_stats where stat_time >= date_sub(now(), interval 1 hour) group by service_name, api_path order by error_count desc limit 10")
        ));
    }

    private static Table table(String name, String description, List<String> aliases, List<Column> columns) {
        Table table = new Table();
        table.setName(name);
        table.setDescription(description);
        table.setAliases(new ArrayList<>(aliases));
        table.setColumns(new ArrayList<>(columns));
        return table;
    }

    private static Column column(String name, String type, String description) {
        Column column = new Column();
        column.setName(name);
        column.setType(type);
        column.setDescription(description);
        return column;
    }

    private static Term term(String name, String description) {
        Term term = new Term();
        term.setName(name);
        term.setDescription(description);
        return term;
    }

    private static Example example(String question, String sql) {
        Example example = new Example();
        example.setQuestion(question);
        example.setSql(sql);
        return example;
    }
}
