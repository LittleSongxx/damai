package org.javaup.ai.rag.intent;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantRouteType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/**
 * Detects ambiguous intent classifications where the top two candidates are too close
 * (gray zone) and generates clarification prompts to guide the user.
 *
 * Inspired by ragent's IntentGuidanceService.
 */
@Slf4j
@Service
public class IntentGuidanceService {

    private final IntentTree intentTree;
    private final ChatClient chatClient;

    @Value("${damai.ai.intent.ambiguity-margin:0.20}")
    private double ambiguityMargin;

    @Value("${damai.ai.intent.min-score:0.4}")
    private double minScore;

    @Value("${damai.ai.intent.max-count:3}")
    private int maxIntentCount;

    public IntentGuidanceService(IntentTree intentTree,
                                  @Qualifier("unifiedGeneralChatClient") ChatClient chatClient) {
        this.intentTree = intentTree;
        this.chatClient = chatClient;
    }

    public GuidanceResult classify(String message) {
        List<IntentNode> leaves = intentTree.getLeaves();
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是大麦票务平台的意图分类器。请根据用户的消息，从以下意图中选出最匹配的。\n\n");
        prompt.append("意图列表：\n");
        for (IntentNode leaf : leaves) {
            prompt.append("- ").append(leaf.getId()).append(": ").append(leaf.getDescription());
            if (leaf.getExamples() != null && !leaf.getExamples().isEmpty()) {
                prompt.append(" (例: ").append(String.join(", ", leaf.getExamples())).append(")");
            }
            prompt.append("\n");
        }
        prompt.append("\n用户消息：").append(message).append("\n");
        prompt.append("\n返回JSON数组，每项包含 id 和 score (0-1)。按score降序，最多3项。\n");
        prompt.append("格式: [{\"id\":\"...\", \"score\":0.9}, ...]");

        java.util.LinkedHashMap<String, Double> scores = new java.util.LinkedHashMap<>();
        try {
            String result = chatClient.prompt().user(prompt.toString()).call().content();
            var parsed = com.alibaba.fastjson2.JSON.parseArray(result);
            if (parsed != null) {
                for (int i = 0; i < parsed.size(); i++) {
                    var obj = parsed.getJSONObject(i);
                    String id = obj.getString("id");
                    Double score = obj.getDouble("score");
                    if (id != null && score != null && score >= minScore) {
                        scores.put(id, score);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Intent classification via LLM failed", e);
        }

        if (scores.isEmpty()) {
            return new GuidanceResult(null, false, "no confident intent found", null);
        }

        var entries = new ArrayList<>(scores.entrySet());
        entries.sort(Entry.<String, Double>comparingByValue().reversed());
        if (entries.size() == 1) {
            String top = entries.get(0).getKey();
            return new GuidanceResult(resolveRouteType(top), false, "single clear intent: " + top, null);
        }

        double topScore = entries.get(0).getValue();
        double secondScore = entries.get(1).getValue();
        String topIntent = entries.get(0).getKey();
        String secondIntent = entries.get(1).getKey();

        if (topScore - secondScore < ambiguityMargin) {
            IntentNode topNode = intentTree.getNodeById().get(topIntent);
            IntentNode secondNode = intentTree.getNodeById().get(secondIntent);
            String clarificationPrompt = buildClarificationPrompt(
                    topNode != null ? topNode.getDescription() : topIntent,
                    secondNode != null ? secondNode.getDescription() : secondIntent);
            log.info("Intent gray zone: {}={:.2f} vs {}={:.2f}, margin={:.2f}",
                    topIntent, topScore, secondIntent, secondScore, ambiguityMargin);
            return new GuidanceResult(null, true,
                    "ambiguity: " + topIntent + " vs " + secondIntent,
                    clarificationPrompt);
        }

        return new GuidanceResult(resolveRouteType(topIntent), false,
                "clear intent: " + topIntent + " (margin=" + String.format("%.2f", topScore - secondScore) + ")", null);
    }

    private AssistantRouteType resolveRouteType(String intentId) {
        IntentNode node = intentTree.getNodeById().get(intentId);
        if (node != null && node.getRouteType() != null) {
            return AssistantRouteType.valueOf(node.getRouteType());
        }
        if (intentId.contains(".")) {
            String parentId = intentId.substring(0, intentId.lastIndexOf('.'));
            return resolveRouteType(parentId);
        }
        return AssistantRouteType.GENERAL;
    }

    private String buildClarificationPrompt(String option1, String option2) {
        return "我还不太确定你的具体需求：\n" +
                "1. " + option1 + "\n" +
                "2. " + option2 + "\n" +
                "请告诉我你主要想做哪一类的操作？";
    }

    public record GuidanceResult(AssistantRouteType routeType,
                                  boolean needsClarification,
                                  String reason,
                                  String clarificationPrompt) {}
}
