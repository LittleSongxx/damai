package org.javaup.ai.assistant.skill.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsRcaEvidenceServiceTest {

    @Test
    void shouldBuildEvidenceBundleFromProvidersOnly() {
        OpsProviderRegistry registry = new OpsProviderRegistry();
        registry.setProviders(List.of(
                provider("logs", Map.of("count", 12)),
                provider("metrics", Map.of("healthStatus", "MEMORY_ALERT")),
                provider("traces", Map.of("traceId", "abc123", "count", 2)),
                provider("alerts", Map.of("items", List.of(Map.of("name", "OrderSuccessRateDrop")))),
                provider("businessEvents", Map.of("items", List.of(Map.of("eventType", "ORDER_CREATE_FAILED")))),
                provider("runbooks", Map.of("items", List.of(Map.of("runbookId", "rb1", "title", "下单成功率下降排查"))))
        ));
        OpsRcaEvidenceService service = new OpsRcaEvidenceService(registry);

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
        assertEquals("HIGH", bundle.get("confidence"));
        Map<?, ?> signals = (Map<?, ?>) bundle.get("signals");
        assertTrue(String.valueOf(signals).contains("OrderSuccessRateDrop"));
        assertTrue(String.valueOf(bundle.get("linkedSignals")).contains("businessEvents"));
        assertTrue(String.valueOf(bundle.get("recommendedRunbooks")).contains("rb1"));
        assertTrue(String.valueOf(bundle.get("missingProviders")).contains("changes"));
        assertEquals(Boolean.TRUE, bundle.get("humanReviewRequired"));
    }

    @Test
    void unavailableProviderShouldNotCountAsCoverageOrLinkedSignal() {
        OpsProviderRegistry registry = new OpsProviderRegistry();
        registry.setProviders(List.of(
                provider("logs", Map.of("count", 1)),
                unavailableProvider("traces", Map.of("configured", false, "traceId", "abc123", "count", 0))
        ));
        OpsRcaEvidenceService service = new OpsRcaEvidenceService(registry);

        OpsRcaRequest request = new OpsRcaRequest();
        request.setQuery("order-service traceId=abc123");

        Map<String, Object> bundle = service.buildEvidenceBundle(request);

        assertTrue(String.valueOf(bundle.get("missingProviders")).contains("traces"));
        assertTrue(String.valueOf(bundle.get("linkedSignals")).contains("logs"));
        assertTrue(!String.valueOf(bundle.get("linkedSignals")).contains("traces"));
        assertEquals(Boolean.TRUE, bundle.get("humanReviewRequired"));
    }

    private OpsEvidenceProvider provider(String signalType, Map<String, Object> payload) {
        return new OpsEvidenceProvider() {
            @Override
            public String name() {
                return signalType + "-test-provider";
            }

            @Override
            public String signalType() {
                return signalType;
            }

            @Override
            public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
                return payload;
            }
        };
    }

    private OpsEvidenceProvider unavailableProvider(String signalType, Map<String, Object> payload) {
        return new OpsEvidenceProvider() {
            @Override
            public String name() {
                return signalType + "-unavailable-test-provider";
            }

            @Override
            public String signalType() {
                return signalType;
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
                return payload;
            }
        };
    }
}
