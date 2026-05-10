package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.llm-fallback")
public class LlmFallbackProperties {

    private String primaryModel = "qwen3.6-plus";
    private String fallbackModel = "qwen-turbo-latest";
    private long timeoutMs = 15000;
}
