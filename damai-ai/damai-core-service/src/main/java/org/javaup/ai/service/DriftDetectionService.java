package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "damai.ai.drift-detection", name = "enabled", havingValue = "true")
public class DriftDetectionService {

    private final ChatClient chatClient;
    private final double sampleRate;

    private static final List<String> CANARY_PROMPTS = List.of(
            "你好，请介绍一下自己",
            "大麦是什么平台？",
            "如何购票？"
    );

    public DriftDetectionService(@Qualifier("unifiedChatClient") ChatClient chatClient,
                                 @org.springframework.beans.factory.annotation.Value("${damai.ai.drift-detection.sample-rate:0.05}") double sampleRate) {
        this.chatClient = chatClient;
        this.sampleRate = sampleRate;
    }

    @Scheduled(cron = "${damai.ai.drift-detection.cron:0 0 3 * * ?}")
    public void runCanaryCheck() {
        log.info("Running drift detection canary check...");
        for (String prompt : CANARY_PROMPTS) {
            try {
                String response = chatClient.prompt().user(prompt).call().content();
                if (response == null || response.length() < 10) {
                    log.warn("Drift detected: empty or short response for canary prompt: {}", prompt);
                } else {
                    log.info("Canary OK: prompt='{}', responseLen={}", prompt, response.length());
                }
            } catch (Exception e) {
                log.error("Drift detection failed for prompt '{}': {}", prompt, e.getMessage());
            }
        }
    }

    public boolean shouldSample() {
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }
}
