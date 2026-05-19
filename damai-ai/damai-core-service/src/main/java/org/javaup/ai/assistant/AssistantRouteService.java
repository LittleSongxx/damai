package org.javaup.ai.assistant;

import org.javaup.ai.rag.intent.IntentGuidanceService;
import org.javaup.ai.structured.IntentRecognition;
import org.javaup.ai.structured.StructuredOutputService;
import org.javaup.ai.utils.CommonUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AssistantRouteService {

    private static final Map<String, Double> BUSINESS_KEYWORDS = Map.ofEntries(
            Map.entry("购票", 2.5), Map.entry("买票", 2.5), Map.entry("门票", 1.5), Map.entry("票档", 2.0),
            Map.entry("演唱会", 1.0), Map.entry("节目", 0.5), Map.entry("推荐", 0.8), Map.entry("下单", 2.5)
    );

    private static final Map<String, Double> KNOWLEDGE_KEYWORDS = Map.ofEntries(
            Map.entry("规则", 2.0), Map.entry("退票", 2.0), Map.entry("退款", 2.0),
            Map.entry("实名", 1.5), Map.entry("转赠", 1.5), Map.entry("儿童票", 1.5), Map.entry("入场", 1.0),
            Map.entry("配送", 1.0), Map.entry("电子票", 1.0), Map.entry("安检", 1.0)
    );

    private static final Map<String, Double> OPS_KEYWORDS = Map.ofEntries(
            Map.entry("trace", 2.0), Map.entry("日志", 2.0), Map.entry("jvm", 2.0), Map.entry("cpu", 2.0),
            Map.entry("线程", 1.5), Map.entry("gc", 1.5), Map.entry("监控", 1.5),
            Map.entry("服务健康", 2.0), Map.entry("消息异常", 1.5), Map.entry("接口调用量", 2.0), Map.entry("接口错误", 2.0)
    );

    private static final Map<String, Double> GENERAL_KEYWORDS = Map.ofEntries(
            Map.entry("谁是", 1.5), Map.entry("是谁", 1.5), Map.entry("介绍", 1.0), Map.entry("代表作", 1.5),
            Map.entry("百科", 1.5), Map.entry("新闻", 1.5), Map.entry("资料", 1.0), Map.entry("最近", 0.5),
            Map.entry("歌手", 1.0), Map.entry("艺人", 1.0), Map.entry("专辑", 1.0), Map.entry("巡演", 1.0),
            Map.entry("新歌", 1.0), Map.entry("乐队", 1.0)
    );

    private final StructuredOutputService structuredOutputService;
    private final ChatClient chatClient;
    private final IntentGuidanceService intentGuidanceService;

    public AssistantRouteService(StructuredOutputService structuredOutputService,
                                 @Qualifier("unifiedGeneralChatClient") ChatClient chatClient,
                                 IntentGuidanceService intentGuidanceService) {
        this.structuredOutputService = structuredOutputService;
        this.chatClient = chatClient;
        this.intentGuidanceService = intentGuidanceService;
    }

    public AssistantRouteDecision route(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (looksLikeOpsDataQuestion(normalized)) {
            return AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.OPS)
                    .reason("keyword:ops-nl2sql")
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        Map.Entry<AssistantRouteType, Double> best = Map.ofEntries(
                Map.entry(AssistantRouteType.BUSINESS, scoreKeywords(normalized, BUSINESS_KEYWORDS)),
                Map.entry(AssistantRouteType.KNOWLEDGE, scoreKeywords(normalized, KNOWLEDGE_KEYWORDS)),
                Map.entry(AssistantRouteType.OPS, scoreKeywords(normalized, OPS_KEYWORDS)),
                Map.entry(AssistantRouteType.GENERAL, scoreKeywords(normalized, GENERAL_KEYWORDS))
        ).entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).orElse(null);

        if (best != null && best.getValue() > 0) {
            // Intent tree gray-zone check for keyword-matched routes
            try {
                var guidanceResult = intentGuidanceService.classify(message);
                if (guidanceResult.needsClarification() && guidanceResult.clarificationPrompt() != null) {
                    return AssistantRouteDecision.builder()
                            .routeType(best.getKey())
                            .reason("keyword_grayzone:" + best.getKey().getCode())
                            .fromFallback(false)
                            .clarificationRequired(true)
                            .clarificationPrompt(guidanceResult.clarificationPrompt())
                            .clarificationOptions(List.of("查询或购买演出票", "咨询购票/退票/入场规则", "联网搜索歌手、演出和娱乐资讯", "排查日志、Trace 或服务指标"))
                            .build();
                }
            } catch (Exception ignored) {
                // Intent guidance is best-effort; fall through to keyword result
            }
            return AssistantRouteDecision.builder()
                    .routeType(best.getKey())
                    .reason("keyword:" + best.getKey().getCode())
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        // Intent tree classification as fallback before LLM structured output
        try {
            var guidanceResult = intentGuidanceService.classify(message);
            if (guidanceResult.routeType() != null && !guidanceResult.needsClarification()) {
                return AssistantRouteDecision.builder()
                        .routeType(guidanceResult.routeType())
                        .reason("intent_tree:" + guidanceResult.reason())
                        .fromFallback(true)
                        .clarificationRequired(false)
                        .build();
            }
            if (guidanceResult.needsClarification() && guidanceResult.clarificationPrompt() != null) {
                return AssistantRouteDecision.builder()
                        .routeType(AssistantRouteType.GENERAL)
                        .reason("intent_tree_ambiguity")
                        .fromFallback(true)
                        .clarificationRequired(true)
                        .clarificationPrompt(guidanceResult.clarificationPrompt())
                        .clarificationOptions(List.of("查询或购买演出票", "咨询购票/退票/入场规则", "联网搜索歌手、演出和娱乐资讯", "排查日志、Trace 或服务指标"))
                        .build();
            }
        } catch (Exception ignored) {
            // Fall through to structured output
        }
        try {
            IntentRecognition recognition = structuredOutputService.recognizeIntent(chatClient, message);
            String primaryIntent = recognition == null ? null : recognition.getPrimaryIntent();
            if (requiresClarification(recognition)) {
                return clarification(recognition, primaryIntent);
            }
            if (!StringUtils.hasText(primaryIntent)) {
                return clarification(recognition, primaryIntent);
            }
            return switch (primaryIntent) {
                case "BUY_TICKET", "QUERY_PROGRAM", "CHECK_ORDER" -> AssistantRouteDecision.builder()
                        .routeType(AssistantRouteType.BUSINESS)
                        .reason("structured:" + primaryIntent)
                        .fromFallback(true)
                        .clarificationRequired(false)
                        .build();
                case "REFUND", "CONSULT" -> AssistantRouteDecision.builder()
                        .routeType(AssistantRouteType.KNOWLEDGE)
                        .reason("structured:" + primaryIntent)
                        .fromFallback(true)
                        .clarificationRequired(false)
                        .build();
                case "OTHER", "GENERAL" -> AssistantRouteDecision.builder()
                        .routeType(AssistantRouteType.GENERAL)
                        .reason("structured:" + primaryIntent)
                        .fromFallback(true)
                        .clarificationRequired(false)
                        .build();
                default -> fallbackGeneral("structured:" + primaryIntent);
            };
        } catch (Exception ignored) {
            return fallbackGeneral("fallback:general");
        }
    }

    private AssistantRouteDecision fallbackGeneral(String reason) {
        return AssistantRouteDecision.builder()
                .routeType(AssistantRouteType.GENERAL)
                .reason(reason)
                .fromFallback(true)
                .clarificationRequired(false)
                .build();
    }

    private boolean requiresClarification(IntentRecognition recognition) {
        if (recognition == null) {
            return true;
        }
        if (Boolean.TRUE.equals(recognition.getNeedsClarification())) {
            return true;
        }
        return recognition.getConfidence() != null && recognition.getConfidence() < 0.45D;
    }

    private AssistantRouteDecision clarification(IntentRecognition recognition, String primaryIntent) {
        String prompt = recognition == null || !StringUtils.hasText(recognition.getClarificationQuestion())
                ? "我还不能准确判断你想办理购票、咨询规则还是排查运维问题。请补充一下你的具体目标。"
                : recognition.getClarificationQuestion();
        return AssistantRouteDecision.builder()
                .routeType(AssistantRouteType.BUSINESS)
                .reason("clarification:" + (StringUtils.hasText(primaryIntent) ? primaryIntent : "UNKNOWN"))
                .fromFallback(false)
                .clarificationRequired(true)
                .clarificationPrompt(prompt)
                .clarificationOptions(List.of("查询或购买演出票", "咨询购票/退票/入场规则", "联网搜索歌手、演出和娱乐资讯", "排查日志、Trace 或服务指标"))
                .build();
    }

    private double scoreKeywords(String text, Map<String, Double> keywords) {
        double score = 0;
        for (Map.Entry<String, Double> entry : keywords.entrySet()) {
            if (text.contains(entry.getKey())) {
                score += entry.getValue();
            }
        }
        return score;
    }

    private boolean looksLikeOpsDataQuestion(String normalized) {
        if (!CommonUtils.containsAny(normalized,
                "nl2sql", "text2sql", "sql", "查库", "数据库", "问数", "取数", "报表",
                "统计", "趋势", "同比", "环比", "排名", "top",
                "订单量", "支付成功率", "退款率", "退款金额", "gmv", "成交额",
                "失败订单", "失败原因", "票档库存", "库存告急", "余票",
                "接口调用量", "接口错误", "错误率", "p95", "消息异常", "消费失败",
                "token", "成本")) {
            return false;
        }
        return !CommonUtils.containsAny(normalized, "购票", "买票", "下单", "推荐");
    }
}
