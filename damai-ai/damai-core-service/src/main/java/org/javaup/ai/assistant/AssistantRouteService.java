package org.javaup.ai.assistant;

import org.javaup.ai.config.AssistantRoutingProperties;
import org.javaup.ai.rag.intent.IntentGuidanceService;
import org.javaup.ai.structured.IntentRecognition;
import org.javaup.ai.structured.StructuredOutputService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class AssistantRouteService {

    private final StructuredOutputService structuredOutputService;
    private final ChatClient chatClient;
    private final IntentGuidanceService intentGuidanceService;
    private final AssistantRoutingProperties routingProperties;

    @Autowired
    public AssistantRouteService(StructuredOutputService structuredOutputService,
                                 @Qualifier("unifiedGeneralChatClient") ChatClient chatClient,
                                 IntentGuidanceService intentGuidanceService,
                                 AssistantRoutingProperties routingProperties) {
        this.structuredOutputService = structuredOutputService;
        this.chatClient = chatClient;
        this.intentGuidanceService = intentGuidanceService;
        this.routingProperties = routingProperties == null ? new AssistantRoutingProperties() : routingProperties;
    }

    public AssistantRouteService(StructuredOutputService structuredOutputService,
                                 ChatClient chatClient,
                                 IntentGuidanceService intentGuidanceService) {
        this(structuredOutputService, chatClient, intentGuidanceService, new AssistantRoutingProperties());
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
                Map.entry(AssistantRouteType.BUSINESS, scoreKeywords(normalized, routingProperties.getBusinessKeywords())),
                Map.entry(AssistantRouteType.KNOWLEDGE, scoreKeywords(normalized, routingProperties.getKnowledgeKeywords())),
                Map.entry(AssistantRouteType.OPS, scoreKeywords(normalized, routingProperties.getOpsKeywords())),
                Map.entry(AssistantRouteType.GENERAL, scoreKeywords(normalized, routingProperties.getGeneralKeywords()))
        ).entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).orElse(null);

        if (best != null && best.getValue() >= routingProperties.getKeywordMinScore()) {
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
                            .clarificationOptions(clarificationOptions())
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
                        .clarificationOptions(clarificationOptions())
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
        return recognition.getConfidence() != null
                && recognition.getConfidence() < routingProperties.getStructuredClarificationConfidence();
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
                .clarificationOptions(clarificationOptions())
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
        if (!containsAny(normalized, routingProperties.getOpsDataKeywords())) {
            return false;
        }
        return !containsAny(normalized, routingProperties.getPurchaseOverrideKeywords());
    }

    private List<String> clarificationOptions() {
        return routingProperties.getClarificationOptions() == null || routingProperties.getClarificationOptions().isEmpty()
                ? new AssistantRoutingProperties().getClarificationOptions()
                : routingProperties.getClarificationOptions();
    }

    private boolean containsAny(String text, List<String> terms) {
        if (!StringUtils.hasText(text) || terms == null || terms.isEmpty()) {
            return false;
        }
        return terms.stream().filter(StringUtils::hasText).anyMatch(text::contains);
    }
}
