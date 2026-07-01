package org.javaup.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class UnifiedChatClientConfiguration {

    @Bean("unifiedChatClient")
    public ChatClient unifiedChatClient(OpenAiChatModel model, LlmFallbackProperties properties) {
        return chatClientForModel(model, properties.getPrimaryModel());
    }

    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(OpenAiChatModel model, LlmFallbackProperties properties) {
        return chatClientForModel(model, properties.getFallbackModel());
    }

    private ChatClient chatClientForModel(OpenAiChatModel model, String modelName) {
        ChatClient.Builder builder = ChatClient.builder(model);
        if (StringUtils.hasText(modelName)) {
            builder.defaultOptions(OpenAiChatOptions.builder()
                    .model(modelName)
                    .build());
        }
        return builder.build();
    }
}
