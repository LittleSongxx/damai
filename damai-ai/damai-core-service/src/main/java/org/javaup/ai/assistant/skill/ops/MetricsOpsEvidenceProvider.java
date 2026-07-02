package org.javaup.ai.assistant.skill.ops;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MetricsOpsEvidenceProvider implements OpsEvidenceProvider {

    private final MetricsGateway metricsGateway;

    @Override
    public String name() {
        return "default-metrics-provider";
    }

    @Override
    public String signalType() {
        return "metrics";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        return metricsGateway.getServiceHealthOverview(request.getServiceName());
    }
}
