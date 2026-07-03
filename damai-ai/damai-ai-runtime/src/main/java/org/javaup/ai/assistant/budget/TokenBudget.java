package org.javaup.ai.assistant.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次 Run 的 token 预算追踪，遵循 Dify 的多级窗口管理设计。
 *
 * <p>将上下文窗口切分为系统提示、记忆、用户画像、检索证据、用户消息、
 * 响应预留等类别，每种类别有独立配额，超出时触发警告或截断。
 */
public class TokenBudget {

    private final String modelName;
    private final int contextWindowTokens;
    private final int responseReserveTokens;
    private final Map<String, Integer> allocations = new LinkedHashMap<>();
    private final Map<String, Integer> usages = new LinkedHashMap<>();
    private volatile boolean budgetExhausted;

    public TokenBudget(String modelName, int contextWindowTokens, int responseReserveTokens) {
        this.modelName = modelName;
        this.contextWindowTokens = contextWindowTokens;
        this.responseReserveTokens = responseReserveTokens;
    }

    public void allocate(String category, int maxTokens) {
        allocations.put(category, maxTokens);
        usages.put(category, 0);
    }

    public void recordUsage(String category, int tokensUsed) {
        usages.merge(category, tokensUsed, Integer::sum);
        if (getRemaining() <= 0) {
            budgetExhausted = true;
        }
    }

    public int getAllocated() {
        return allocations.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getUsed() {
        return usages.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getRemaining() {
        return contextWindowTokens - getUsed() - responseReserveTokens;
    }

    public int getAllocatedFor(String category) {
        return allocations.getOrDefault(category, 0);
    }

    public int getUsedFor(String category) {
        return usages.getOrDefault(category, 0);
    }

    public boolean isBudgetExhausted() {
        return budgetExhausted;
    }

    public boolean isApproachingLimit() {
        return getRemaining() < contextWindowTokens * 0.15;
    }

    public String getWarningMessage() {
        return String.format(
                "Token budget warning: model=%s, used=%d/%d (%.0f%%), remaining=%d, responseReserve=%d",
                modelName, getUsed(), contextWindowTokens,
                getUsed() * 100.0 / contextWindowTokens, getRemaining(), responseReserveTokens);
    }

    public String getDetailMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("TokenBudget[model=%s, window=%d, used=%d, remaining=%d]",
                modelName, contextWindowTokens, getUsed(), getRemaining()));
        for (Map.Entry<String, Integer> entry : allocations.entrySet()) {
            String category = entry.getKey();
            sb.append(String.format("\n  %s: %d/%d", category, getUsedFor(category), entry.getValue()));
        }
        return sb.toString();
    }

    public String getModelName() {
        return modelName;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getResponseReserveTokens() {
        return responseReserveTokens;
    }

    public Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> snap = new LinkedHashMap<>();
        for (String category : allocations.keySet()) {
            snap.put(category, Map.of(
                    "allocated", allocations.getOrDefault(category, 0),
                    "used", usages.getOrDefault(category, 0)
            ));
        }
        return snap;
    }
}
