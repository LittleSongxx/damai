package org.javaup.ai.assistant;

import org.javaup.ai.entity.AiRunEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssistantRunEventStreamService {

    private final Map<String, Sinks.Many<AiRunEvent>> runSinks = new ConcurrentHashMap<>();

    public Flux<AiRunEvent> stream(String runId) {
        return sink(runId).asFlux();
    }

    public void publish(AiRunEvent event) {
        if (event == null || event.getRunId() == null) {
            return;
        }
        Sinks.Many<AiRunEvent> sink = sink(event.getRunId());
        sink.tryEmitNext(event);
        if (isTerminal(event.getEventType())) {
            sink.tryEmitComplete();
            runSinks.remove(event.getRunId(), sink);
        }
    }

    public void ensureRunStream(String runId) {
        sink(runId);
    }

    private Sinks.Many<AiRunEvent> sink(String runId) {
        return runSinks.computeIfAbsent(runId, ignored -> Sinks.many().replay().limit(512));
    }

    private boolean isTerminal(String eventType) {
        return AssistantEventTypes.RUN_COMPLETED.equals(eventType) || AssistantEventTypes.RUN_FAILED.equals(eventType);
    }
}
