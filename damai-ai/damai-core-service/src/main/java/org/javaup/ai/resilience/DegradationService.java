package org.javaup.ai.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DegradationService {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public DegradationService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public DegradationContext begin(String operation) {
        return new DegradationContext(operation);
    }

    public class DegradationContext {
        private final String operation;
        private String degradedComponent;
        private boolean degraded;

        private DegradationContext(String operation) {
            this.operation = operation;
        }

        public void degradedTo(String component) {
            this.degraded = true;
            this.degradedComponent = component;
            counter("damai.degradation." + operation + "." + component).increment();
            log.warn("{} 降级到 {}", operation, component);
        }

        public void skipped(String component, String reason) {
            this.degraded = true;
            this.degradedComponent = "skipped";
            counter("damai.degradation." + operation + ".skipped").increment();
            log.warn("{} 跳过 {}: {}", operation, component, reason);
        }

        public boolean isDegraded() { return degraded; }
        public String degradedComponent() { return degradedComponent; }
    }

    private Counter counter(String name) {
        return counters.computeIfAbsent(name,
                k -> Counter.builder(k).description("Degradation counter").register(meterRegistry));
    }
}
