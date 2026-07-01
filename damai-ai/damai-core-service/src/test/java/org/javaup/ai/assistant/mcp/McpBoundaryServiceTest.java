package org.javaup.ai.assistant.mcp;

import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.runtime.AssistantRunGraphService;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.mapper.AiToolAuditMapper;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.vo.AssistantRunGraphVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpBoundaryServiceTest {

    private final AiPermissionService permissionService = mock(AiPermissionService.class);
    private final AiToolAuditMapper auditMapper = mock(AiToolAuditMapper.class);
    private final LogGateway logGateway = mock(LogGateway.class);
    private final MetricsGateway metricsGateway = mock(MetricsGateway.class);
    private final AssistantRunGraphService runGraphService = mock(AssistantRunGraphService.class);
    private final McpGovernanceProperties properties = new McpGovernanceProperties();
    private final McpToolGovernanceService governanceService =
            new McpToolGovernanceService(properties, permissionService, auditMapper);
    private final McpBoundaryService boundaryService =
            new McpBoundaryService(governanceService, logGateway, metricsGateway, runGraphService);

    @AfterEach
    void tearDown() {
        AiRequestContextHolder.clear();
    }

    @Test
    void shouldReadAllowlistedResourceAndAuditAccess() {
        properties.setRequireAdmin(false);
        AiRequestContextHolder.set(AiRequestContext.builder()
                .user(AiUserContext.builder().userId(9L).admin(true).build())
                .build());
        when(logGateway.getServiceList()).thenReturn(Map.of("services", java.util.List.of("order-service"), "count", 1));

        Map<String, Object> result = boundaryService.readResource("observability://logs/services", Map.of());

        assertEquals("resource", result.get("surface"));
        assertEquals("observability://logs/services", result.get("name"));
        assertTrue(String.valueOf(result.get("content")).contains("order-service"));
        verify(auditMapper).insert(any(AiToolAudit.class));
    }

    @Test
    void shouldRequireConfirmationForHighRiskResourceAndPrompt() {
        properties.setRequireAdmin(false);

        assertThrows(AiAuthorizationException.class,
                () -> boundaryService.readResource("assistant://runs/{runId}/graph", Map.of("runId", "run-1")));
        assertThrows(AiAuthorizationException.class,
                () -> boundaryService.renderPrompt("ops.rca", Map.of("serviceName", "order-service")));

        AssistantRunGraphVo graph = AssistantRunGraphVo.builder()
                .runId("run-1")
                .nodes(java.util.List.of())
                .edges(java.util.List.of())
                .build();
        when(runGraphService.buildGraph("run-1")).thenReturn(graph);

        Map<String, Object> resource = boundaryService.readResource(
                "assistant://runs/{runId}/graph",
                Map.of("runId", "run-1", "confirmed", true));
        Map<String, Object> prompt = boundaryService.renderPrompt(
                "ops.rca",
                Map.of("serviceName", "order-service", "traceId", "trace-1", "confirmed", true));

        assertTrue(String.valueOf(resource.get("content")).contains("run-1"));
        assertTrue(String.valueOf(prompt.get("template")).contains("RCA evidence bundle"));
        assertTrue(String.valueOf(prompt.get("requiredEvidence")).contains("SLO/error budget"));
        verify(auditMapper, org.mockito.Mockito.times(2)).insert(any(AiToolAudit.class));
    }
}
