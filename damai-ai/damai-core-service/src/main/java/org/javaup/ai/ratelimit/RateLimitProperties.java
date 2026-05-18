package org.javaup.ai.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Map<String, EndpointLimit> endpoints = Map.of(
            "assistant.runs.create", new EndpointLimit(20, 60, "user"),
            "assistant.runs.stream", new EndpointLimit(10, 0, "user"),
            "simple.chat", new EndpointLimit(10, 1, "ip"),
            "admin", new EndpointLimit(50, 60, "user")
    );

    @Data
    public static class EndpointLimit {
        private int limit;
        private int windowSeconds;
        private String keyType;

        public EndpointLimit() {}
        public EndpointLimit(int limit, int windowSeconds, String keyType) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
            this.keyType = keyType;
        }
    }
}
