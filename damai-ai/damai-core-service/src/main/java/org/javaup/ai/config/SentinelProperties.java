package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.sentinel")
public class SentinelProperties {

    private ResourceRule llm = resource("ai.llm.primary", 20, 10000, 30, 15000);
    private ResourceRule qdrant = resource("ai.rag.qdrant", 10, 10000, 15, 3000);
    private ResourceRule es = resource("ai.rag.elasticsearch", 10, 10000, 15, 3000);
    private ResourceRule webSearch = resource("ai.web-search", 10, 10000, 30, 5000);
    private ResourceRule userService = resource("ai.user-service", 10, 10000, 30, 3000);

    private static ResourceRule resource(String resourceName,
                                         int minRequestAmount,
                                         int statIntervalMs,
                                         int timeWindowSeconds,
                                         long slowRequestThresholdMs) {
        ResourceRule rule = new ResourceRule();
        rule.setResourceName(resourceName);
        rule.getDegrade().setMinRequestAmount(minRequestAmount);
        rule.getDegrade().setStatIntervalMs(statIntervalMs);
        rule.getDegrade().setTimeWindowSeconds(timeWindowSeconds);
        rule.getDegrade().setSlowRequestThresholdMs(slowRequestThresholdMs);
        return rule;
    }

    @Data
    public static class ResourceRule {
        private boolean enabled = true;
        private String resourceName;
        private Degrade degrade = new Degrade();
        private Flow flow = new Flow();
    }

    @Data
    public static class Degrade {
        private boolean enabled = true;
        private String grade = "exception_ratio";
        private double exceptionRatio = 0.5;
        private int exceptionCount = 5;
        private long slowRequestThresholdMs = 3000;
        private double slowRatioThreshold = 0.8;
        private int minRequestAmount = 10;
        private int statIntervalMs = 10000;
        private int timeWindowSeconds = 30;
    }

    @Data
    public static class Flow {
        private boolean enabled = true;
        private String grade = "qps";
        private double count = 100;
    }
}
