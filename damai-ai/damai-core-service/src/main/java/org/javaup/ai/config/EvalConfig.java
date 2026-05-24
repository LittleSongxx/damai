package org.javaup.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public void setDegradedMode(boolean degradedMode) {
        this.degradedMode = degradedMode;
    }

    public double getRecallAtKThreshold() {
        return recallAtKThreshold;
    }

    public void setRecallAtKThreshold(double recallAtKThreshold) {
        this.recallAtKThreshold = recallAtKThreshold;
    }

    public double getMrrThreshold() {
        return mrrThreshold;
    }

    public void setMrrThreshold(double mrrThreshold) {
        this.mrrThreshold = mrrThreshold;
    }

    public double getNdcgAtKThreshold() {
        return ndcgAtKThreshold;
    }

    public void setNdcgAtKThreshold(double ndcgAtKThreshold) {
        this.ndcgAtKThreshold = ndcgAtKThreshold;
    }

    public double getContextPrecisionThreshold() {
        return contextPrecisionThreshold;
    }

    public void setContextPrecisionThreshold(double contextPrecisionThreshold) {
        this.contextPrecisionThreshold = contextPrecisionThreshold;
    }

    public double getContextRecallThreshold() {
        return contextRecallThreshold;
    }

    public void setContextRecallThreshold(double contextRecallThreshold) {
        this.contextRecallThreshold = contextRecallThreshold;
    }

    public double getContextRelevanceThreshold() {
        return contextRelevanceThreshold;
    }

    public void setContextRelevanceThreshold(double contextRelevanceThreshold) {
        this.contextRelevanceThreshold = contextRelevanceThreshold;
    }

    public double getFaithfulnessThreshold() {
        return faithfulnessThreshold;
    }

    public void setFaithfulnessThreshold(double faithfulnessThreshold) {
        this.faithfulnessThreshold = faithfulnessThreshold;
    }

    public double getAnswerRelevancyThreshold() {
        return answerRelevancyThreshold;
    }

    public void setAnswerRelevancyThreshold(double answerRelevancyThreshold) {
        this.answerRelevancyThreshold = answerRelevancyThreshold;
    }

    public double getAnswerCorrectnessThreshold() {
        return answerCorrectnessThreshold;
    }

    public void setAnswerCorrectnessThreshold(double answerCorrectnessThreshold) {
        this.answerCorrectnessThreshold = answerCorrectnessThreshold;
    }
}
