package org.javaup.ai.assistant.skill.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class CorrectiveQueryService {

    private final ChatClient chatClient;

    public CorrectiveQueryService(@Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public CorrectiveQueryPlan planAmbiguous(String query, String missingInfo) {
        String missingSlotQuery = missingSlotRewrite(query, missingInfo);
        return new CorrectiveQueryPlan(
                missingSlotQuery,
                "",
                missingInfo == null ? "" : missingInfo,
                "AMBIGUOUS questions use missing-slot rewrite instead of wider duplicate retrieval"
        );
    }

    public CorrectiveQueryPlan planIncorrect(String query, String missingInfo) {
        String stepBack = stepBackQuery(query, missingInfo);
        String reformulated = reformulatedQuery(query, missingInfo, stepBack);
        return new CorrectiveQueryPlan(
                reformulated,
                stepBack,
                missingInfo == null ? "" : missingInfo,
                "INCORRECT questions use step-back plus reformulated query"
        );
    }

    String missingSlotRewrite(String query, String missingInfo) {
        if (!StringUtils.hasText(query)) return "";
        if (!StringUtils.hasText(missingInfo)) {
            return query;
        }
        try {
            String prompt = """
                    你是大麦智能客服的检索查询改写器。用户问题的首轮证据缺少关键信息，请生成一个更适合补充检索的查询。
                    要求：
                    1. 保留原始意图，不扩大到无关主题
                    2. 显式补入缺失槽位或缺失条件
                    3. 只输出一行检索查询，不要解释

                    原始问题：%s
                    缺失信息：%s
                    补充检索查询：
                    """.formatted(query, missingInfo);
            String raw = chatClient.prompt().user(prompt).call().content();
            return firstLineOrFallback(raw, query);
        } catch (Exception e) {
            log.warn("Missing-slot query rewrite failed, using deterministic fallback: {}", e.getMessage());
            return query + " " + missingInfo;
        }
    }

    String stepBackQuery(String query, String missingInfo) {
        if (!StringUtils.hasText(query)) return "";
        try {
            String prompt = """
                    你是大麦票务规则检索专家。请把用户的具体问题抽象成上位规则查询，用于检索总规则或原则性说明。
                    要求：
                    1. 不要生成答案
                    2. 不要包含订单号、手机号、身份证等用户私密信息
                    3. 只输出一行上位规则检索词

                    用户问题：%s
                    缺失信息：%s
                    上位规则检索词：
                    """.formatted(query, missingInfo == null ? "" : missingInfo);
            String raw = chatClient.prompt().user(prompt).call().content();
            return firstLineOrFallback(raw, fallbackStepBack(query));
        } catch (Exception e) {
            log.warn("Step-back query failed, using deterministic fallback: {}", e.getMessage());
            return fallbackStepBack(query);
        }
    }

    private String reformulatedQuery(String query, String missingInfo, String stepBack) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(query)) builder.append(query.trim());
        if (StringUtils.hasText(missingInfo)) builder.append(' ').append(missingInfo.trim());
        if (StringUtils.hasText(stepBack) && !builder.toString().contains(stepBack.trim())) {
            builder.append(' ').append(stepBack.trim());
        }
        return builder.toString().trim();
    }

    private String fallbackStepBack(String query) {
        if (!StringUtils.hasText(query)) return "";
        if (query.contains("退") || query.contains("退款")) return "票务退票退款规则 适用条件 手续费 到账";
        if (query.contains("实名") || query.contains("身份证")) return "实名购票 观演人证件 入场核验规则";
        if (query.contains("入场") || query.contains("二维码")) return "电子票二维码 入场检票 凭证规则";
        if (query.contains("支付") || query.contains("扣款")) return "订单支付 扣款 退款 支付超时规则";
        return query + " 平台规则 适用条件 操作流程";
    }

    private String firstLineOrFallback(String raw, String fallback) {
        if (!StringUtils.hasText(raw)) return fallback;
        return raw.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(fallback);
    }

    public record CorrectiveQueryPlan(
            String query,
            String stepBackQuery,
            String missingInfo,
            String reason
    ) {
    }
}
