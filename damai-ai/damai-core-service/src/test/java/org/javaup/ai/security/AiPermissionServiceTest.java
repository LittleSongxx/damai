package org.javaup.ai.security;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.config.AiSecurityProperties;
import org.javaup.ai.context.AiUserContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPermissionServiceTest {

    @Test
    void shouldParseAdminUserIdsFromConfiguration() {
        AiSecurityProperties properties = new AiSecurityProperties();
        properties.setAdminUserIds("1001, 1002");
        AiPermissionService service = new AiPermissionService(properties);

        assertTrue(service.isAdmin(1001L));
        assertTrue(service.isAdmin(1002L));
        assertFalse(service.isAdmin(1003L));
    }

    @Test
    void shouldAllowOpsOnlyForAdminUsers() {
        AiSecurityProperties properties = new AiSecurityProperties();
        AiPermissionService service = new AiPermissionService(properties);
        AiUserContext admin = AiUserContext.builder().userId(1L).admin(true).build();
        AiUserContext user = AiUserContext.builder().userId(2L).admin(false).build();

        assertTrue(service.canAccessRoute(admin, AssistantRouteType.OPS));
        assertFalse(service.canAccessRoute(user, AssistantRouteType.OPS));
        assertTrue(service.canAccessRoute(user, AssistantRouteType.BUSINESS));
        assertTrue(service.canAccessRoute(user, AssistantRouteType.KNOWLEDGE));
        assertTrue(service.canAccessRoute(user, AssistantRouteType.GENERAL));
    }
}
