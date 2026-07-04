package org.javaup.ai.security;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.UserContextCacheService;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.vo.UserDetailVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import org.javaup.ai.ai.function.call.UserCall;

/**
 * 通过 damai-pro 的用户服务解析登录态。
 */
@Slf4j
@Service
public class AiAuthenticationService {

    @Resource
    private UserCall userCall;

    @Resource
    private AiPermissionService aiPermissionService;

    @Resource
    private UserContextCacheService userContextCacheService;

    @Resource
    private CircuitBreakerService circuitBreakerService;

    public AiUserContext authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            throw new AiAuthenticationException("登录态缺失");
        }
        AiUserContext cached = userContextCacheService.get(token);
        if (cached != null) {
            return cached;
        }
        UserDetailVo user = circuitBreakerService.executeUserService(
                () -> userCall.currentUser(token),
                null);
        if (user == null) {
            throw new AiAuthenticationException("用户认证服务暂不可用，请稍后重试");
        }
        AiUserContext context = AiUserContext.builder()
                .userId(user.getId())
                .token(token)
                .mobile(user.getMobile())
                .name(user.getName())
                .email(user.getEmail())
                .admin(aiPermissionService.isAdmin(user.getId()))
                .build();
        userContextCacheService.put(token, context);
        return context;
    }
}
