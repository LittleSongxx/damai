package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class Nl2SqlIntentDetector {

    public boolean isNl2SqlQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "nl2sql", "text2sql", "sql", "查库", "数据库", "问数", "取数", "报表",
                "统计", "趋势", "同比", "环比", "排名", "top", "top10",
                "订单量", "支付成功率", "退款率", "退款金额", "gmv", "成交额",
                "失败订单", "失败原因", "票档库存", "库存告急", "余票",
                "接口调用量", "接口错误", "错误率", "p95", "消息异常", "消费失败",
                "token", "成本"
        );
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
