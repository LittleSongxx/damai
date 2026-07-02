package org.javaup.ai.assistant.skill.ops;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.gateway.TraceGateway;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TraceOpsEvidenceProvider implements OpsEvidenceProvider {

    private final TraceGateway traceGateway;

    @Override
    public String name() {
        return "skywalking-trace-provider";
    }

    @Override
    public String signalType() {
        return "traces";
    }

    @Override
    public boolean available() {
        return traceGateway.available();
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        if (!StringUtils.hasText(request.getTraceId())) {
            return Map.of("configured", available(), "traceId", "", "count", 0, "spans", List.of());
        }
        return traceGateway.getTrace(request.getTraceId(), start, end);
    }
}
