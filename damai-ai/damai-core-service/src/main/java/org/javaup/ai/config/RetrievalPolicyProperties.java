package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.retrieval.policy")
public class RetrievalPolicyProperties {

    private List<String> highRiskTerms = List.of(
            "退票", "退款", "退钱", "实名", "身份证", "入场", "支付", "扣款", "订单", "发票", "投诉", "赔偿", "取消");
    private List<String> abstractRuleTerms = List.of(
            "规则", "政策", "限制", "条件", "流程", "怎么办", "如何", "能不能", "可以吗");
    private List<String> businessKeyTerms = List.of(
            "订单", "手机号", "身份证", "二维码", "票档", "座位", "场次", "节目", "支付", "退款");
    private int rerankMinTopK = 6;
    private int correctiveTopKMultiplier = 2;
}
