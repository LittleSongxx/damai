package org.javaup.ai.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.ResilienceProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
public class CircuitBreakerService {

    private final CircuitBreakerRegistry registry;
    private final ResilienceProperties properties;

    public CircuitBreakerService(ResilienceProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.registry = CircuitBreakerRegistry.ofDefaults();
    }

    // --- LLM ---

    public <T> T executeLlm(Supplier<T> callable, T fallback) {
        if (!properties.getLlm().isEnabled()) return callable.get();
        return execute("llm-primary", buildLlmConfig(), callable, fallback);
    }

    private CircuitBreakerConfig buildLlmConfig() {
        var llm = properties.getLlm();
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(llm.getFailureRateThreshold())
                .slidingWindowSize(llm.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(llm.getWaitDurationSeconds()))
                .permittedNumberOfCallsInHalfOpenState(llm.getPermittedCallsInHalfOpen())
                .slowCallDurationThreshold(Duration.ofMillis(llm.getTimeoutMs()))
                .slowCallRateThreshold(80)
                .build();
    }

    // --- Qdrant ---

    public <T> T executeQdrant(Supplier<T> callable, T fallback) {
        if (!properties.getQdrant().isEnabled()) return callable.get();
        return execute("qdrant", buildConfig(properties.getQdrant()), callable, fallback);
    }

    // --- Elasticsearch ---

    public <T> T executeEs(Supplier<T> callable, T fallback) {
        if (!properties.getEs().isEnabled()) return callable.get();
        return execute("elasticsearch", buildConfig(properties.getEs()), callable, fallback);
    }

    // --- Web search ---

    public <T> T executeWebSearch(Supplier<T> callable, T fallback) {
        if (!properties.getWebSearch().isEnabled()) return callable.get();
        return execute("web-search", buildConfig(properties.getWebSearch()), callable, fallback);
    }

    // --- generic ---

    private <T> T execute(String name, CircuitBreakerConfig config, Supplier<T> callable, T fallback) {
        CircuitBreaker cb = registry.circuitBreaker(name, config);
        try {
            return CircuitBreaker.decorateSupplier(cb, callable).get();
        } catch (Exception e) {
            log.warn("Circuit breaker {} triggered: {}", name, e.getMessage());
            return fallback;
        }
    }

    private CircuitBreakerConfig buildConfig(ResilienceProperties.QdrantCircuitBreaker props) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .slidingWindowSize(props.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(props.getWaitDurationSeconds()))
                .build();
    }

    private CircuitBreakerConfig buildConfig(ResilienceProperties.EsCircuitBreaker props) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .slidingWindowSize(props.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(props.getWaitDurationSeconds()))
                .build();
    }

    private CircuitBreakerConfig buildConfig(ResilienceProperties.WebSearchCircuitBreaker props) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .slidingWindowSize(props.getSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(props.getWaitDurationSeconds()))
                .build();
    }
}
