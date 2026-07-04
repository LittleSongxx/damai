package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "damai.ai.eval")
public class EvalConfig {

    private boolean degradedMode = false;
    private double recallAtKThreshold = 0.85;
    private double mrrThreshold = 0.75;
    private double ndcgAtKThreshold = 0.80;
    private double contextPrecisionThreshold = 0.75;
    private double contextRecallThreshold = 0.75;
    private double contextRelevanceThreshold = 0.70;
    private double faithfulnessThreshold = 0.90;
    private double answerRelevancyThreshold = 0.80;
    private double answerCorrectnessThreshold = 0.75;
    private int maxConcurrency = 3;
    private int workerPoolSize = 3;
    private int innerPoolSize = 4;
    private int runTimeoutMinutes = 30;
    private int stageTimeoutMinutes = 10;
}
