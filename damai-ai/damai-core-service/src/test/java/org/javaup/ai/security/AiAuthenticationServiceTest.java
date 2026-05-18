package org.javaup.ai.security;

import org.javaup.ai.ai.function.call.UserCall;
import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.vo.UserDetailVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAuthenticationServiceTest {

    @Mock
    private UserCall userCall;

    @Mock
    private AiPermissionService aiPermissionService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private CircuitBreakerService circuitBreakerService;

    @InjectMocks
    private AiAuthenticationService authenticationService;

    @Test
    void shouldRejectBlankToken() {
        assertThrows(AiAuthenticationException.class, () -> authenticationService.authenticate(" "));
    }

    @Test
    void shouldResolveCurrentUserFromDamaiPro() {
        UserDetailVo user = new UserDetailVo();
        user.setId(2002L);
        user.setName("测试用户");
        user.setMobile("13800000000");
        user.setEmail("demo@test.com");
        when(userCall.currentUser("token-2002")).thenReturn(user);
        when(aiPermissionService.isAdmin(2002L)).thenReturn(false);
        when(circuitBreakerService.executeWebSearch(any(), any())).thenAnswer(inv -> inv.getArgument(0, java.util.function.Supplier.class).get());

        AiUserContext context = authenticationService.authenticate("token-2002");

        assertEquals(2002L, context.getUserId());
        assertEquals("13800000000", context.getMobile());
        assertEquals("demo@test.com", context.getEmail());
        assertFalse(context.getAdmin());
    }
}
