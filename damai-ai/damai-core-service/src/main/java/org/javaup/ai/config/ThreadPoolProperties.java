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
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class Nl2sql {
        private int maxPoolSize = 5;
        private int connectionTimeoutMs = 3000;
        private int idleTimeoutMs = 600000;
        private int maxLifetimeMs = 1800000;
    }
}
