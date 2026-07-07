package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.cache")
public class CacheProperties {

    private Embedding embedding = new Embedding();
    private FaqSearch faqSearch = new FaqSearch();
    private WebSearch webSearch = new WebSearch();
    private UserContext userContext = new UserContext();
    private Nl2sqlSchema nl2sqlSchema = new Nl2sqlSchema();
    private Nl2sqlResult nl2sqlResult = new Nl2sqlResult();
    private RagEvidence ragEvidence = new RagEvidence();

    @Data
    public static class Embedding {
        private boolean enabled = true;
        private int maxSize = 5000;
        private int ttlMinutes = 30;
        /** Whether to persist embeddings to Redis for cross-restart durability */
        private boolean redisPersistenceEnabled = false;
        /** Redis key prefix for persisted embeddings */
        private String redisKeyPrefix = "damai:cache:embed:";
    }

    @Data
    public static class FaqSearch {
        private boolean enabled = true;
        private int ttlMinutes = 15;
    }

    @Data
    public static class WebSearch {
        private boolean enabled = true;
        private int ttlMinutes = 5;
    }

    @Data
    public static class UserContext {
        private boolean enabled = true;
        private int ttlMinutes = 10;
    }

    @Data
    public static class Nl2sqlSchema {
        private boolean enabled = true;
        private int maxSize = 100;
        private int ttlMinutes = 60;
    }

    @Data
    public static class Nl2sqlResult {
        private boolean enabled = true;
        private int ttlMinutes = 3;
    }

    @Data
    public static class RagEvidence {
        private boolean enabled = true;
        private int ttlMinutes = 15;
        private int maxSize = 1000;
        private boolean semanticEnabled = true;
        private double semanticThreshold = 0.93D;
        private int semanticMaxSize = 1000;
    }
}
