package org.javaup.ai.assistant.mcp;

import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.mapper.AiToolAuditMapper;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpToolGovernanceServiceTest {

    private final AiPermissionService permissionService = mock(AiPermissionService.class);
    private final AiToolAuditMapper auditMapper = mock(AiToolAuditMapper.class);

    @AfterEach
    void tearDown() {
        AiRequestContextHolder.clear();
    }

    @Test
    void shouldAuditAllowedToolCall() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(false);
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);
        AiRequestContextHolder.set(AiRequestContext.builder()
                .runId("run-1")
                .conversationId("chat-1")
                .user(AiUserContext.builder().userId(7L).admin(true).build())
                .build());

        Map<String, Object> result = service.execute("log.getServiceList", Map.of("scope", "all"),
                () -> Map.of("count", 1));

        assertEquals(1, result.get("count"));
        verify(auditMapper).insert(any(AiToolAudit.class));
    }

    @Test
    void shouldRejectNonAllowlistedToolWithoutAudit() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(false);
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        assertThrows(AiAuthorizationException.class,
                () -> service.execute("internal.deleteEverything", Map.of(), () -> "nope"));
        verify(auditMapper, never()).insert(any(AiToolAudit.class));
    }

    @Test
    void shouldRejectNl2SqlUnlessExplicitlyExposed() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(false);
        properties.setExposeNl2Sql(false);
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        assertThrows(AiAuthorizationException.class,
                () -> service.execute("nl2sql.nl2sqlQuery", Map.of("question", "订单量"), () -> Map.of()));
    }

    @Test
    void shouldRequireExplicitConfirmationForExposedHighRiskTool() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(false);
        properties.setExposeNl2Sql(true);
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        assertThrows(AiAuthorizationException.class,
                () -> service.execute("nl2sql.nl2sqlQuery", Map.of("question", "订单量"), () -> Map.of()));

        Map<String, Object> result = service.execute("nl2sql.nl2sqlQuery",
                Map.of("question", "订单量", "confirmed", true),
                () -> Map.of("status", "SQL_READY"));

        assertEquals("SQL_READY", result.get("status"));
        verify(auditMapper).insert(any(AiToolAudit.class));
    }

    @Test
    void shouldRequireAdminForAllowedToolWhenConfigured() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(true);
        doThrow(new AiAuthorizationException("no ops")).when(permissionService).requireOpsAccess();
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        assertThrows(AiAuthorizationException.class,
                () -> service.execute("log.getServiceList", Map.of(), () -> Map.of()));
        verify(auditMapper, never()).insert(any(AiToolAudit.class));
    }

    @Test
    void shouldExposeGovernanceSnapshotForAdminWorkspace() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        Map<String, Object> snapshot = service.governanceSnapshot();

        assertEquals("PASS", snapshot.get("status"));
        assertEquals(Boolean.TRUE, snapshot.get("requireHighRiskConfirmation"));
        assertEquals(Boolean.FALSE, snapshot.get("exposeNl2Sql"));
        assertEquals(properties.getAllowlist().size(), snapshot.get("allowlistSize"));
        assertEquals(properties.getResourceAllowlist().size(), snapshot.get("resourceAllowlistSize"));
        assertEquals(properties.getPromptAllowlist().size(), snapshot.get("promptAllowlistSize"));
        assertEquals(true, String.valueOf(snapshot.get("allowlistedTools")).contains("log.getServiceList"));
        assertEquals(true, String.valueOf(snapshot.get("allowlistedResources")).contains("assistant://runs/{runId}/graph"));
        assertEquals(true, String.valueOf(snapshot.get("allowlistedPrompts")).contains("ops.rca"));
        assertEquals(true, String.valueOf(snapshot.get("highRiskTools")).contains("requiresConfirmation=true"));
        assertEquals(true, String.valueOf(snapshot.get("highRiskResources")).contains("requiresConfirmation=true"));
        assertEquals(true, String.valueOf(snapshot.get("highRiskPrompts")).contains("requiresConfirmation=true"));
        assertEquals(true, String.valueOf(snapshot.get("protocolSurfaces")).contains("resources"));
    }

    @Test
    void shouldAuthorizeResourcesAndPromptsWithAllowlistAndConfirmation() {
        McpGovernanceProperties properties = new McpGovernanceProperties();
        properties.setRequireAdmin(false);
        McpToolGovernanceService service = new McpToolGovernanceService(properties, permissionService, auditMapper);

        service.authorizeResource("observability://logs/services", Map.of());
        service.authorizePrompt("ops.log-diagnosis", Map.of());

        assertThrows(AiAuthorizationException.class,
                () -> service.authorizeResource("assistant://runs/{runId}/graph", Map.of()));
        service.authorizeResource("assistant://runs/{runId}/graph", Map.of("confirmed", true));

        assertThrows(AiAuthorizationException.class,
                () -> service.authorizePrompt("ops.rca", Map.of()));
        service.authorizePrompt("ops.rca", Map.of("confirmed", true));

        assertThrows(AiAuthorizationException.class,
                () -> service.authorizeResource("filesystem://etc/passwd", Map.of("confirmed", true)));
        assertThrows(AiAuthorizationException.class,
                () -> service.authorizePrompt("internal.secret-prompt", Map.of("confirmed", true)));
    }
}
