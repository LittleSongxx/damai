package org.javaup.ai.security;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.security.AiPermissionService;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * AI 接口登录态解析。
 */
@Component
public class AiAuthenticationInterceptor implements HandlerInterceptor {

    private static final Set<String> OPEN_PREFIXES = Set.of(
            "/error"
    );

    @Value("${damai.ai.playground.enabled:false}")
    private boolean playgroundEnabled;

    private final AiAuthenticationService authenticationService;
    private final AiPermissionService permissionService;
    private final AccessDomainPolicyService accessDomainPolicyService;

    public AiAuthenticationInterceptor(AiAuthenticationService authenticationService,
                                       AiPermissionService permissionService,
                                       AccessDomainPolicyService accessDomainPolicyService) {
        this.authenticationService = authenticationService;
        this.permissionService = permissionService;
        this.accessDomainPolicyService = accessDomainPolicyService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (OPEN_PREFIXES.stream().anyMatch(uri::startsWith)) {
            return true;
        }
        if (playgroundEnabled && uri.startsWith("/simple/")) {
            return true;
        }
        try {
            String token = request.getHeader("token");
            AiUserContext userContext = authenticationService.authenticate(token);
            AiRequestContextHolder.set(AiRequestContext.builder().user(userContext).build());
            if (accessDomainPolicyService.isInternalDev(uri) && !playgroundEnabled) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "当前接口仅用于内部调试，未开启 playground");
                AiRequestContextHolder.clear();
                return false;
            }
            if (accessDomainPolicyService.requiresAdmin(uri) && !permissionService.isAdmin(userContext)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "当前账号无权访问 AI 管理、评测、运维或索引治理接口");
                AiRequestContextHolder.clear();
                return false;
            }
            return true;
        } catch (AiAuthenticationException ex) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AiRequestContextHolder.clear();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(ApiResponse.error(status, message)));
    }
}
