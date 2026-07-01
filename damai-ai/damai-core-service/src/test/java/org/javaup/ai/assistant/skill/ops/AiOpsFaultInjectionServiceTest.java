package org.javaup.ai.assistant.skill.ops;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiOpsFaultInjectionServiceTest {

    private final AiOpsFaultInjectionService service = new AiOpsFaultInjectionService();

    @Test
    void shouldListProductionLikeFaultScenarios() {
        assertEquals(5, service.listScenarios().size());
        assertTrue(service.listScenarios().stream().anyMatch(scenario -> "db-slow-query".equals(scenario.getScenarioId())));
        assertTrue(service.listScenarios().stream().allMatch(scenario -> scenario.getRcaPrompt().contains("traceId=")));
    }

    @Test
    void shouldInjectSimulationOnlyEvidenceBundle() {
        OpsRcaRequest request = new OpsRcaRequest();
        request.setWindowMinutes(15);
        request.setServiceName("order-service");

        Map<String, Object> result = service.injectScenario("error-spike", request);

        assertEquals("SIMULATED", result.get("status"));
        assertTrue(String.valueOf(result.get("assistantPrompt")).contains("日志、指标、trace、span、SLO"));
        Map<?, ?> evidenceBundle = (Map<?, ?>) result.get("evidenceBundle");
        assertEquals("application error spike", evidenceBundle.get("suspectedCause"));
        assertEquals("HIGH", evidenceBundle.get("confidence"));
        assertEquals("error-spike-span-001", evidenceBundle.get("spanId"));
        Map<?, ?> slo = (Map<?, ?>) evidenceBundle.get("slo");
        assertEquals(Boolean.TRUE, slo.get("errorBudgetBurning"));
        assertEquals("CRITICAL", slo.get("severity"));
        assertTrue(((Number) slo.get("burnRate")).doubleValue() >= 4.0);
        Map<?, ?> alertContext = (Map<?, ?>) evidenceBundle.get("alertContext");
        assertEquals(Boolean.TRUE, alertContext.get("triggered"));
        assertEquals("order-service", alertContext.get("serviceName"));
        assertEquals("oncall:order-service", alertContext.get("routingHint"));
        assertTrue(String.valueOf(evidenceBundle.get("playbookHints")).contains("slo-playbook"));
        assertTrue(String.valueOf(evidenceBundle.get("serviceTopology")).contains("order-service"));
        assertTrue(String.valueOf(evidenceBundle.get("signalCorrelation")).contains("STRONG"));
        assertTrue(String.valueOf(evidenceBundle.get("signalCorrelation")).contains("error-spike-span-001"));
        assertTrue(String.valueOf(((Map<?, ?>) evidenceBundle.get("signals")).get("logAggregation")).contains("bySpanId"));
        assertTrue(String.valueOf(evidenceBundle.get("recentChanges")).contains("release"));
        assertTrue(String.valueOf(evidenceBundle.get("evidenceTimeline")).contains("trace"));
        assertEquals("CRITICAL", ((Map<?, ?>) evidenceBundle.get("rcaSummary")).get("sloSeverity"));
        assertEquals("error-spike-span-001", ((Map<?, ?>) evidenceBundle.get("rcaSummary")).get("firstFailingSpanId"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) evidenceBundle.get("rcaSummary")).get("alertTriggered"));
        Map<?, ?> safety = (Map<?, ?>) result.get("safety");
        assertEquals(Boolean.TRUE, safety.get("simulationOnly"));
        assertEquals(Boolean.FALSE, safety.get("writesBusinessData"));
    }

    @Test
    void shouldReturnNotFoundForUnknownScenario() {
        Map<String, Object> result = service.injectScenario("missing", null);

        assertEquals("NOT_FOUND", result.get("status"));
        assertFalse(result.containsKey("evidenceBundle"));
    }
}
