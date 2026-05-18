package org.javaup.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> skillCalls = new ConcurrentHashMap<>();
    private final Map<String, Counter> skillErrors = new ConcurrentHashMap<>();
    private final Counter nl2sqlSuccess;
    private final Counter nl2sqlFailure;
    private final Counter degradationEvents;
    private final Timer assistantRunTimer;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.nl2sqlSuccess = Counter.builder("damai.nl2sql.success")
                .description("NL2SQL query successes").register(registry);
        this.nl2sqlFailure = Counter.builder("damai.nl2sql.failure")
                .description("NL2SQL query failures").register(registry);
        this.degradationEvents = Counter.builder("damai.degradation.events")
                .description("Degradation events count").register(registry);
        this.assistantRunTimer = Timer.builder("damai.assistant.run.duration")
                .description("Assistant run duration").register(registry);
    }

    public void recordSkillCall(String skillId) {
        skillCalls.computeIfAbsent(skillId, k ->
                Counter.builder("damai.skill.calls").tag("skill", skillId)
                        .description("Skill call count").register(registry)
        ).increment();
    }

    public void recordSkillError(String skillId) {
        skillErrors.computeIfAbsent(skillId, k ->
                Counter.builder("damai.skill.errors").tag("skill", skillId)
                        .description("Skill error count").register(registry)
        ).increment();
    }

    public void recordNl2sql(boolean success) {
        if (success) nl2sqlSuccess.increment(); else nl2sqlFailure.increment();
    }

    public void recordDegradation() {
        degradationEvents.increment();
    }

    public Timer.Sample startAssistantRun() {
        return Timer.start(registry);
    }

    public void stopAssistantRun(Timer.Sample sample) {
        sample.stop(assistantRunTimer);
    }

    public void recordAssistantRun(long durationMs) {
        registry.timer("damai.assistant.run.duration").record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordRetrievalDuration(String source, long durationMs) {
        registry.timer("damai.retrieval.duration", "source", source)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordModelCall(String model, int inputTokens, int outputTokens) {
        registry.counter("damai.model.calls", "model", model).increment();
        registry.counter("damai.model.input_tokens", "model", model).increment(inputTokens);
        registry.counter("damai.model.output_tokens", "model", model).increment(outputTokens);
    }
}
