package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.LlmFallbackProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@Service
public class ResilientChatService {

    private final ChatClient primaryClient;
    private final ChatClient fallbackClient;
    private final LlmFallbackProperties properties;

    public ResilientChatService(@Qualifier("unifiedChatClient") ChatClient primaryClient,
                                @Qualifier("fallbackChatClient") ChatClient fallbackClient,
                                LlmFallbackProperties properties) {
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
        this.properties = properties;
    }

    public String call(String userPrompt) {
        try {
            ChatResponse response = primaryClient.prompt()
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getText();
            }
            throw new RuntimeException("Primary model returned empty response");
        } catch (Exception e) {
            log.warn("Primary model ({}) failed, switching to fallback ({}): {}",
                    properties.getPrimaryModel(), properties.getFallbackModel(), e.getMessage());
            return callFallback(userPrompt);
        }
    }

    public Flux<String> stream(String userPrompt) {
        return primaryClient.prompt()
                .user(userPrompt)
                .stream()
                .content()
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .onErrorResume(ex -> {
                    log.warn("Primary model stream failed, switching to fallback: {}", ex.getMessage());
                    return fallbackClient.prompt()
                            .user(userPrompt)
                            .stream()
                            .content();
                });
    }

    private String callFallback(String userPrompt) {
        try {
            ChatResponse response = fallbackClient.prompt()
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getText();
            }
            return "抱歉，当前 AI 服务暂时不可用，请稍后重试。";
        } catch (Exception fallbackError) {
            log.error("Fallback model also failed: {}", fallbackError.getMessage());
            return "抱歉，当前 AI 服务暂时不可用，请稍后重试。";
        }
    }
}
