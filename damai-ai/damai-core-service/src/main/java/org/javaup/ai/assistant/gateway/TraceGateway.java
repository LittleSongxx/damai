package org.javaup.ai.assistant.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class TraceGateway {

    private final LogGateway logGateway;

    public Map<String, Object> getTrace(String traceId) {
        return logGateway.getLogsByTraceId(traceId);
    }
}
