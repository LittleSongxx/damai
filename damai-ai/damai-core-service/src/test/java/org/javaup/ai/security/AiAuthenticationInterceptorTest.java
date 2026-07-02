package org.javaup.ai.security;

import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAuthenticationInterceptorTest {

    private final AiAuthenticationService authenticationService = mock(AiAuthenticationService.class);
    private final AiPermissionService permissionService = mock(AiPermissionService.class);
    private final AccessDomainPolicyService accessDomainPolicyService = new AccessDomainPolicyService();
    private final AiAuthenticationInterceptor interceptor =
            new AiAuthenticationInterceptor(authenticationService, permissionService, accessDomainPolicyService);

    @AfterEach
    void tearDown() {
        AiRequestContextHolder.clear();
    }

    @Test
    void shouldRejectAnonymousRagEvalAccess() throws Exception {
        when(authenticationService.authenticate(null)).thenThrow(new AiAuthenticationException("登录态缺失"));
        MockHttpServletRequest request = request("/assistant/admin/rag-eval/start", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(401, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldRejectNonAdminForGovernanceEndpoints() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        when(permissionService.isAdmin(user)).thenReturn(false);
        MockHttpServletRequest request = request("/assistant/admin/knowledge/reindex-jobs", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(403, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldRejectNonAdminForUnifiedEvalControlPlane() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        when(permissionService.isAdmin(user)).thenReturn(false);
        MockHttpServletRequest request = request("/assistant/evals/rag/run", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(403, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldRejectNonAdminForFeedbackKnowledgeGaps() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        when(permissionService.isAdmin(user)).thenReturn(false);
        MockHttpServletRequest request = request("/assistant/admin/customer-service/feedback/knowledge-gaps", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(403, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldRejectNonAdminForObservabilityEndpoints() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        when(permissionService.isAdmin(user)).thenReturn(false);
        MockHttpServletRequest request = request("/assistant/admin/observability/today", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(403, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldRejectNonAdminForActuatorEndpoints() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        when(permissionService.isAdmin(user)).thenReturn(false);
        MockHttpServletRequest request = request("/actuator/prometheus", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(403, response.getStatus());
        assertNull(AiRequestContextHolder.get());
    }

    @Test
    void shouldAllowAdminForGovernanceEndpoints() throws Exception {
        AiUserContext admin = AiUserContext.builder().userId(1L).admin(true).build();
        when(authenticationService.authenticate("token-admin")).thenReturn(admin);
        when(permissionService.isAdmin(admin)).thenReturn(true);
        MockHttpServletRequest request = request("/assistant/admin/nl2sql-eval/start", "token-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals(200, response.getStatus());
        assertEquals(admin, AiRequestContextHolder.getRequiredUser());
    }

    @Test
    void shouldAllowAdminForActuatorEndpoints() throws Exception {
        AiUserContext admin = AiUserContext.builder().userId(1L).admin(true).build();
        when(authenticationService.authenticate("token-admin")).thenReturn(admin);
        when(permissionService.isAdmin(admin)).thenReturn(true);
        MockHttpServletRequest request = request("/actuator/health", "token-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals(200, response.getStatus());
        assertEquals(admin, AiRequestContextHolder.getRequiredUser());
    }

    @Test
    void shouldAllowNormalAssistantEndpointForAuthenticatedUser() throws Exception {
        AiUserContext user = AiUserContext.builder().userId(3L).admin(false).build();
        when(authenticationService.authenticate("token-user")).thenReturn(user);
        MockHttpServletRequest request = request("/assistant/runs", "token-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertEquals(user, AiRequestContextHolder.getRequiredUser());
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("token", token);
        }
        return request;
    }
}
