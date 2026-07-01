package org.javaup.ai.resilience;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.SentinelProperties;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Slf4j
@Service
public class CircuitBreakerService {

    private final SentinelProperties properties;

    public CircuitBreakerService(SentinelProperties properties) {
        this.properties = properties;
    }

    // --- LLM ---

    public <T> T executeLlm(Supplier<T> callable, T fallback) {
        return execute(properties.getLlm(), callable, fallback);
    }

    public <T> T executeLlmLazy(Supplier<T> callable, Supplier<T> fallbackSupplier) {
        return executeLazy(properties.getLlm(), callable, fallbackSupplier);
    }

    // --- Qdrant ---

    public <T> T executeQdrant(Supplier<T> callable, T fallback) {
        return execute(properties.getQdrant(), callable, fallback);
    }

    // --- Elasticsearch ---

    public <T> T executeEs(Supplier<T> callable, T fallback) {
        return execute(properties.getEs(), callable, fallback);
    }

    // --- Web search ---

    public <T> T executeWebSearch(Supplier<T> callable, T fallback) {
        return execute(properties.getWebSearch(), callable, fallback);
    }

    public <T> T executeUserService(Supplier<T> callable, T fallback) {
        return execute(properties.getUserService(), callable, fallback);
    }

    // --- generic ---

    private <T> T execute(SentinelProperties.ResourceRule rule, Supplier<T> callable, T fallback) {
        return executeLazy(rule, callable, () -> fallback);
    }

    private <T> T executeLazy(SentinelProperties.ResourceRule rule, Supplier<T> callable, Supplier<T> fallbackSupplier) {
        if (rule == null || !rule.isEnabled()) {
            return callable.get();
        }
        try (Entry ignored = SphU.entry(rule.getResourceName())) {
            return callable.get();
        } catch (BlockException ex) {
            log.warn("Sentinel blocked resource {}: {}", rule.getResourceName(), ex.getClass().getSimpleName());
            return fallbackSupplier.get();
        } catch (Exception ex) {
            Tracer.trace(ex);
            log.warn("Sentinel protected resource {} failed: {}", rule.getResourceName(), ex.getMessage());
            return fallbackSupplier.get();
        }
    }
}
