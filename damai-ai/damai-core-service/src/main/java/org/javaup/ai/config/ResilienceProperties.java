package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.resilience")
public class ResilienceProperties {

    private LlmCircuitBreaker llm = new LlmCircuitBreaker();
    private QdrantCircuitBreaker qdrant = new QdrantCircuitBreaker();
    private EsCircuitBreaker es = new EsCircuitBreaker();
    private WebSearchCircuitBreaker webSearch = new WebSearchCircuitBreaker();

    @Data
    public static class LlmCircuitBreaker {
        private boolean enabled = true;
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 10;
        private int waitDurationSeconds = 30;
        private int permittedCallsInHalfOpen = 3;
        private long timeoutMs = 15000;
    }

    @Data
    public static class QdrantCircuitBreaker {
        private boolean enabled = true;
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 5;
        private int waitDurationSeconds = 15;
    }

    @Data
    public static class EsCircuitBreaker {
        private boolean enabled = true;
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 5;
        private int waitDurationSeconds = 15;
    }

    @Data
    public static class WebSearchCircuitBreaker {
        private boolean enabled = true;
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 5;
        private int waitDurationSeconds = 30;
    }
}
