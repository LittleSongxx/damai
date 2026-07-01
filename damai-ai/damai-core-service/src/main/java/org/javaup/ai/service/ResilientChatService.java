package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.budget.QuotaTracker;
import org.javaup.ai.config.AiSecurityProperties;
import org.javaup.ai.config.LlmFallbackProperties;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.metrics.BusinessMetrics;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@Service
public class ResilientChatService {

    private static final String BUDGET_EXHAUSTED_MSG = "您今日的AI调用额度已用完，请明日再试或联系管理员提升额度。";
    private static final String AI_UNAVAILABLE_MSG = "抱歉，当前 AI 服务暂时不可用，请稍后重试。";

    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    private final LlmFallbackProperties properties;
    private final CircuitBreakerService circuitBreakerService;
    private final BusinessMetrics businessMetrics;
    private final AiObservabilityService observabilityService;
    private final AiSecurityProperties securityProperties;
    private final QuotaTracker quotaTracker;

    public ResilientChatService(@Qualifier("unifiedChatClient") ChatClient primaryClient,
                                @Qualifier("fallbackChatClient") ChatClient fallbackClient,
                                LlmFallbackProperties properties,
                                CircuitBreakerService circuitBreakerService,
                                BusinessMetrics businessMetrics,
                                AiObservabilityService observabilityService,
                                AiSecurityProperties securityProperties,
                                QuotaTracker quotaTracker) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.properties = properties;
        this.circuitBreakerService = circuitBreakerService;
        this.businessMetrics = businessMetrics;
        this.observabilityService = observabilityService;
        this.securityProperties = securityProperties;
        this.quotaTracker = quotaTracker;
    }

    public String call(String userPrompt) {
        if (isBudgetExhausted()) {
            return BUDGET_EXHAUSTED_MSG;
        }
        return circuitBreakerService.executeLlmLazy(
                () -> callPrimary(userPrompt),
                () -> {
                    log.warn("Primary model ({}) failed or was blocked, switching to fallback ({})",
                            properties.getPrimaryModel(), properties.getFallbackModel());
                    return callFallback(userPrompt);
                });
    }

    public Flux<String> stream(String userPrompt) {
        if (isBudgetExhausted()) {
            return Flux.just(BUDGET_EXHAUSTED_MSG);
        }
        long dailyBudget = securityProperties.getDailyTokenBudget();
        return primaryClient.prompt()
                .user(userPrompt)
                .stream()
                .content()
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .takeUntil(token -> checkStreamingBudget(dailyBudget))
                .onErrorResume(ex -> {
                    log.warn("Primary model stream failed, switching to fallback: {}", ex.getMessage());
                    return fallbackClient.prompt()
                            .user(userPrompt)
                            .stream()
                            .content();
                });
    }

    private String callPrimary(String userPrompt) {
        ChatResponse response = primaryClient.prompt()
                .user(userPrompt)
                .call()
                .chatResponse();
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            String text = response.getResult().getOutput().getText();
            var usage = response.getMetadata() != null && response.getMetadata().getUsage() != null
                    ? response.getMetadata().getUsage() : null;
            if (usage != null) {
                int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                businessMetrics.recordModelCall(properties.getPrimaryModel(), promptTokens, completionTokens);
                recordQuotaUsage(promptTokens + (long) completionTokens);
            }
            return text;
        }
        throw new IllegalStateException("Primary model returned empty response");
    }

    private boolean checkStreamingBudget(long dailyBudget) {
        if (dailyBudget <= 0) {
            return false;
        }
        try {
            Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
            if (quotaTracker.isBudgetExhausted(userId, dailyBudget)) {
                log.warn("Streaming aborted: daily budget {} exhausted for user {}", dailyBudget, userId);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void recordQuotaUsage(long tokens) {
        try {
            Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
            quotaTracker.recordUsage(userId, tokens);
        } catch (Exception e) {
            log.debug("Failed to record quota usage: {}", e.getMessage());
        }
    }

    private boolean isBudgetExhausted() {
        long budget = securityProperties.getDailyTokenBudget();
        if (budget <= 0) {
            return false;
        }
        try {
            Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
            if (quotaTracker.isBudgetExhausted(userId, budget)) {
                return true;
            }
            return observabilityService.isDailyBudgetExceeded(userId, budget);
        } catch (Exception e) {
            return false;
        }
    }

    private String callFallback(String userPrompt) {
        try {
            ChatResponse response = fallbackClient.prompt()
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                var usage = response.getMetadata() != null && response.getMetadata().getUsage() != null
                        ? response.getMetadata().getUsage() : null;
                if (usage != null) {
                    int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                    int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                    businessMetrics.recordModelCall(properties.getFallbackModel(), promptTokens, completionTokens);
                    recordQuotaUsage(promptTokens + (long) completionTokens);
                }
                return text;
            }
            return AI_UNAVAILABLE_MSG;
        } catch (Exception fallbackError) {
            log.error("Fallback model also failed: {}", fallbackError.getMessage());
            return AI_UNAVAILABLE_MSG;
        }
    }
}
