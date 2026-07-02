package org.javaup.ai.assistant.skill.ops;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LogOpsEvidenceProvider implements OpsEvidenceProvider {

    private final LogGateway logGateway;

    @Override
    public String name() {
        return "default-log-provider";
    }

    @Override
    public String signalType() {
        return "logs";
    }

    @Override
    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        return logGateway.searchLogsByKeyword(request.getQuery(), request.getServiceName(), "ERROR", 30);
    }
}
