package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.llm-fallback")
public class LlmFallbackProperties {

    private String primaryModel = "deepseek-v4-pro";
    private String fallbackModel = "deepseek-chat";
    private long timeoutMs = 15000;
}
