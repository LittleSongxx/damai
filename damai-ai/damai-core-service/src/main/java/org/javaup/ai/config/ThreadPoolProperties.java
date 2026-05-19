package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.thread-pool")
public class ThreadPoolProperties {

    private Assistant assistant = new Assistant();
    private Nl2sql nl2sql = new Nl2sql();

    @Data
    public static class Assistant {
        private int corePoolSize = 16;
        private int maxPoolSize = 64;
        private int queueCapacity = 500;
        private int keepAliveSeconds = 60;
        private double overflowThreshold = 0.8;
    }

    @Data
    public static class Nl2sql {
        private int maxPoolSize = 5;
        private int connectionTimeoutMs = 3000;
        private int idleTimeoutMs = 600000;
        private int maxLifetimeMs = 1800000;
    }
}
