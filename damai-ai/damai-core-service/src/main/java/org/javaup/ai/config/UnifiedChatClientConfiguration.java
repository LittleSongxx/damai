package org.javaup.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnifiedChatClientConfiguration {

    @Bean("unifiedChatClient")
    public ChatClient unifiedChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }

    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model).build();
    }
}
