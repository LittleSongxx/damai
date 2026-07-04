package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.assistant-routing")
public class AssistantRoutingProperties {

    private double keywordMinScore = 0.1D;
    private double structuredClarificationConfidence = 0.45D;
    private List<String> clarificationOptions = List.of(
            "查询或购买演出票",
            "咨询购票/退票/入场规则",
            "联网搜索歌手、演出和娱乐资讯",
            "排查日志、Trace 或服务指标");
    private Map<String, Double> businessKeywords = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("购票", 2.5D), Map.entry("买票", 2.5D), Map.entry("门票", 1.5D), Map.entry("票档", 2.0D),
            Map.entry("演唱会", 1.0D), Map.entry("节目", 0.5D), Map.entry("推荐", 0.8D), Map.entry("下单", 2.5D)
    ));
    private Map<String, Double> knowledgeKeywords = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("规则", 2.0D), Map.entry("退票", 2.0D), Map.entry("退款", 2.0D),
            Map.entry("实名", 1.5D), Map.entry("转赠", 1.5D), Map.entry("儿童票", 1.5D), Map.entry("入场", 1.0D),
            Map.entry("配送", 1.0D), Map.entry("电子票", 1.0D), Map.entry("安检", 1.0D)
    ));
    private Map<String, Double> opsKeywords = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("trace", 2.0D), Map.entry("日志", 2.0D), Map.entry("jvm", 2.0D), Map.entry("cpu", 2.0D),
            Map.entry("线程", 1.5D), Map.entry("gc", 1.5D), Map.entry("监控", 1.5D),
            Map.entry("服务健康", 2.0D), Map.entry("消息异常", 1.5D), Map.entry("接口调用量", 2.0D), Map.entry("接口错误", 2.0D)
    ));
    private Map<String, Double> generalKeywords = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("谁是", 1.5D), Map.entry("是谁", 1.5D), Map.entry("介绍", 1.0D), Map.entry("代表作", 1.5D),
            Map.entry("百科", 1.5D), Map.entry("新闻", 1.5D), Map.entry("资料", 1.0D), Map.entry("最近", 0.5D),
            Map.entry("歌手", 1.0D), Map.entry("艺人", 1.0D), Map.entry("专辑", 1.0D), Map.entry("巡演", 1.0D),
            Map.entry("新歌", 1.0D), Map.entry("乐队", 1.0D)
    ));
    private List<String> opsDataKeywords = List.of(
            "nl2sql", "text2sql", "sql", "查库", "数据库", "问数", "取数", "报表",
            "统计", "趋势", "同比", "环比", "排名", "top",
            "订单量", "支付成功率", "退款率", "退款金额", "gmv", "成交额",
            "失败订单", "失败原因", "票档库存", "库存告急", "余票",
            "接口调用量", "接口错误", "错误率", "p95", "消息异常", "消费失败",
            "token", "成本");
    private List<String> purchaseOverrideKeywords = List.of("购票", "买票", "下单", "推荐");
}
