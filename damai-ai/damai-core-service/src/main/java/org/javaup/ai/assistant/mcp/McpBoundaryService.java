package org.javaup.ai.assistant.mcp;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.runtime.AssistantRunGraphService;
import org.javaup.ai.vo.AssistantRunGraphVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class McpBoundaryService {

    private final McpToolGovernanceService governanceService;
    private final LogGateway logGateway;
    private final MetricsGateway metricsGateway;
    private final AssistantRunGraphService runGraphService;

    public Map<String, Object> readResource(String resourceUri, Map<String, Object> request) {
        return governanceService.accessResource(resourceUri, request, () -> resourcePayload(resourceUri, request));
    }

    public Map<String, Object> renderPrompt(String promptName, Map<String, Object> request) {
        return governanceService.renderPrompt(promptName, request, () -> promptPayload(promptName, request));
    }

    private Map<String, Object> resourcePayload(String resourceUri, Map<String, Object> request) {
        Map<String, Object> payload = envelope("resource", resourceUri);
        if ("observability://logs/services".equals(resourceUri)) {
            payload.put("content", logGateway.getServiceList());
            payload.put("contentType", "application/json");
            return payload;
        }
        if ("observability://metrics/services".equals(resourceUri)) {
            payload.put("content", metricsGateway.getServiceList());
            payload.put("contentType", "application/json");
            return payload;
        }
        if ("observability://traces/{traceId}".equals(resourceUri)) {
            String traceId = text(request.get("traceId"));
            payload.put("content", StringUtils.hasText(traceId)
                    ? logGateway.getLogsByTraceId(traceId)
                    : Map.of("traceId", "", "error", "traceId is required"));
            payload.put("contentType", "application/json");
            return payload;
        }
        if ("assistant://runs/{runId}/graph".equals(resourceUri)) {
            String runId = text(request.get("runId"));
            AssistantRunGraphVo graph = StringUtils.hasText(runId) ? runGraphService.buildGraph(runId) : null;
            payload.put("content", graph == null ? Map.of("runId", runId, "error", "run graph not found") : graph);
            payload.put("contentType", "application/json");
            return payload;
        }
        payload.put("content", Map.of("error", "unsupported resource"));
        payload.put("contentType", "application/json");
        return payload;
    }

    private Map<String, Object> promptPayload(String promptName, Map<String, Object> request) {
        Map<String, Object> payload = envelope("prompt", promptName);
        String serviceName = defaultText(request.get("serviceName"), "order-service");
        String traceId = text(request.get("traceId"));
        String window = defaultText(request.get("windowMinutes"), "30");
        String query = defaultText(request.get("query"), "diagnose recent production incident");
        List<String> requiredEvidence = List.of("logs", "metrics", "trace", "recent changes", "SLO/error budget");
        String template;
        if ("ops.rca".equals(promptName)) {
            template = "Diagnose service=" + serviceName
                    + ", traceId=" + traceId
                    + ", windowMinutes=" + window
                    + ". Build an RCA evidence bundle with logs, metrics, traces, topology, recent changes, SLO burn, suspected cause, and rollback/action suggestions.";
        } else if ("ops.log-diagnosis".equals(promptName)) {
            template = "Analyze logs for service=" + serviceName
                    + ", traceId=" + traceId
                    + ", windowMinutes=" + window
                    + ". Summarize error fingerprints, affected spans, and next log queries.";
            requiredEvidence = List.of("logs", "traceId", "spanId", "error fingerprints");
        } else if ("ops.metrics-diagnosis".equals(promptName)) {
            template = "Analyze metrics for service=" + serviceName
                    + ", windowMinutes=" + window
                    + ". Include PromQL range evidence, saturation/error/latency signals, and SLO impact.";
            requiredEvidence = List.of("metrics", "promql-range", "SLO/error budget");
        } else {
            template = query;
        }
        payload.put("arguments", Map.of(
                "query", query,
                "serviceName", serviceName,
                "traceId", traceId,
                "windowMinutes", window));
        payload.put("requiredEvidence", requiredEvidence);
        payload.put("template", template);
        payload.put("contentType", "text/plain");
        return payload;
    }

    private Map<String, Object> envelope(String surface, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("surface", surface);
        payload.put("name", name);
        payload.put("governed", true);
        payload.put("policy", "explicit allowlist + admin + high-risk confirmation + audit");
        return payload;
    }

    private String defaultText(Object value, String fallback) {
        String text = text(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
