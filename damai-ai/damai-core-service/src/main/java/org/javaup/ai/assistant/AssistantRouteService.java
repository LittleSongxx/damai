package org.javaup.ai.assistant;

import org.javaup.ai.structured.IntentRecognition;
import org.javaup.ai.structured.StructuredOutputService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AssistantRouteService {

    private final StructuredOutputService structuredOutputService;
    private final ChatClient chatClient;
    
    public AssistantRouteService(StructuredOutputService structuredOutputService,
                                 @Qualifier("unifiedGeneralChatClient") ChatClient chatClient) {
        this.structuredOutputService = structuredOutputService;
        this.chatClient = chatClient;
    }

    public AssistantRouteDecision route(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (containsAny(normalized, "购票", "买票", "门票", "票档", "演唱会", "节目", "推荐", "下单")) {
            return AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.BUSINESS)
                    .reason("keyword:business")
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        if (containsAny(normalized, "规则", "退票", "退款", "实名", "转赠", "儿童票", "入场")) {
            return AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.KNOWLEDGE)
                    .reason("keyword:knowledge")
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        if (containsAny(normalized, "trace", "日志", "jvm", "cpu", "线程", "gc", "监控", "服务健康")) {
            return AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.OPS)
                    .reason("keyword:ops")
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        if (containsAny(normalized, "谁是", "是谁", "介绍", "代表作", "百科", "新闻", "资料", "最近", "歌手", "艺人", "专辑", "巡演", "新歌", "乐队")) {
            return AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.GENERAL)
                    .reason("keyword:general")
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
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

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
