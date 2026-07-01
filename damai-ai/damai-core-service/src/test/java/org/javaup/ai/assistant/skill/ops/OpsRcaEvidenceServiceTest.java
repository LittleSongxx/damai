package org.javaup.ai.assistant.skill.ops;

import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.gateway.TraceGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsRcaEvidenceServiceTest {

    @Test
    void shouldBuildEvidenceBundleWithSloAndSuspectedCause() {
        LogGateway logGateway = mock(LogGateway.class);
        MetricsGateway metricsGateway = mock(MetricsGateway.class);
        TraceGateway traceGateway = mock(TraceGateway.class);
        OpsRcaEvidenceService service = new OpsRcaEvidenceService(logGateway, metricsGateway, traceGateway);

        when(logGateway.searchLogsByKeyword(anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("count", 12, "logs", List.of(Map.of(
                        "timestamp", "2026-06-30T10:00:00Z",
                        "service", "order-service",
                        "spanId", "span-order-1",
                        "level", "ERROR",
                        "sourceClass", "OrderService",
                        "sourceMethod", "createOrder",
                        "message", "OutOfMemoryError in order-service"))))
                .thenReturn(Map.of("count", 3, "logs", List.of(Map.of(
                        "timestamp", "2026-06-30T10:00:10Z",
                        "service", "order-service",
                        "spanId", "span-order-1",
                        "level", "WARN",
                        "message", "GC overhead"))));
        when(metricsGateway.getServiceHealthOverview("order-service")).thenReturn(Map.of(
                "serviceName", "order-service",
                "healthStatus", "MEMORY_ALERT",
                "jvmMemory", Map.of("usageRate", "95.00%")));
        when(traceGateway.getTrace("abc123")).thenReturn(Map.of(
                "traceId", "abc123",
                "count", 2,
                "services", List.of("gateway-service", "order-service", "payment-service"),
                "logs", List.of(
                        Map.of("timestamp", "2026-06-30T09:59:59Z", "service", "gateway-service", "spanId", "span-gw-1", "level", "INFO", "message", "request accepted"),
                        Map.of("timestamp", "2026-06-30T10:00:00Z", "service", "order-service", "spanId", "span-order-1", "level", "ERROR", "message", "OutOfMemoryError"))));
        when(metricsGateway.queryRange(anyString(), any(Instant.class), any(Instant.class), any(Duration.class)))
                .thenReturn(Map.of("status", "success", "series", List.of()));

        OpsRcaRequest request = new OpsRcaRequest();
        request.setQuery("order-service traceId=abc123 最近大量失败");
        request.setSpanId("span-order-1");
        request.setWindowMinutes(15);
        request.setReleaseVersion("order-20260630.1");
        request.setConfigKey("order.checkout.timeout");

        Map<String, Object> bundle = service.buildEvidenceBundle(request);

        assertEquals("order-service", bundle.get("serviceName"));
        assertEquals("abc123", bundle.get("traceId"));
        assertEquals("span-order-1", bundle.get("spanId"));
        assertEquals("JVM memory pressure", bundle.get("suspectedCause"));
        assertEquals("HIGH", bundle.get("confidence"));
        Map<?, ?> slo = (Map<?, ?>) bundle.get("slo");
        assertTrue((Boolean) slo.get("errorBudgetBurning"));
        assertEquals("HIGH", slo.get("severity"));
        assertEquals("DamaiAiSloBurnRate", slo.get("alertName"));
        assertTrue(((Number) slo.get("burnRate")).doubleValue() >= 2.0);
        assertTrue(((Number) slo.get("errorBudgetRemaining")).doubleValue() < 1.0);
        Map<?, ?> alertContext = (Map<?, ?>) bundle.get("alertContext");
        assertEquals(Boolean.TRUE, alertContext.get("triggered"));
        assertEquals("order-service", alertContext.get("serviceName"));
        assertEquals("oncall:order-service", alertContext.get("routingHint"));
        assertTrue(String.valueOf(bundle.get("playbookHints")).contains("jvm-memory-playbook"));
        assertTrue(String.valueOf(bundle.get("evidenceItems")).contains("error_logs=12"));
        assertTrue(String.valueOf(bundle.get("suggestedActions")).contains("SLO burn"));
        assertTrue(String.valueOf(bundle.get("suggestedActions")).contains("heap usage"));
        assertNotNull(((Map<?, ?>) bundle.get("timeWindow")).get("start"));
        Map<?, ?> topology = (Map<?, ?>) bundle.get("serviceTopology");
        assertEquals("gateway-service", topology.get("rootService"));
        assertEquals("order-service", topology.get("suspectService"));
        assertTrue(String.valueOf(topology.get("dependencyEdges")).contains("payment-service"));
        Map<?, ?> recentChanges = (Map<?, ?>) bundle.get("recentChanges");
        assertEquals("HIGH", recentChanges.get("riskLevel"));
        assertTrue(String.valueOf(recentChanges.get("items")).contains("order-20260630.1"));
        assertTrue(String.valueOf(bundle.get("evidenceTimeline")).contains("OutOfMemoryError"));
        Map<?, ?> signals = (Map<?, ?>) bundle.get("signals");
        assertTrue(String.valueOf(signals.get("traceSummary")).contains("firstFailingService"));
        assertTrue(String.valueOf(signals.get("traceSummary")).contains("span-order-1"));
        assertTrue(String.valueOf(signals.get("logAggregation")).contains("OrderService#createOrder"));
        Map<?, ?> correlation = (Map<?, ?>) bundle.get("signalCorrelation");
        assertEquals("STRONG", correlation.get("evidenceCompleteness"));
        assertTrue(String.valueOf(correlation.get("linkedSignals")).contains("trace"));
        assertEquals("span-order-1", ((Map<?, ?>) correlation.get("serviceLabels")).get("spanId"));
        assertEquals("order-service", ((Map<?, ?>) bundle.get("rcaSummary")).get("firstFailingService"));
        assertEquals("span-order-1", ((Map<?, ?>) bundle.get("rcaSummary")).get("firstFailingSpanId"));
        assertEquals("HIGH", ((Map<?, ?>) bundle.get("rcaSummary")).get("sloSeverity"));
        assertTrue(((Number) ((Map<?, ?>) bundle.get("rcaSummary")).get("correlationCoverageScore")).doubleValue() >= 0.8);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) bundle.get("rcaSummary")).get("alertTriggered"));
    }
}
