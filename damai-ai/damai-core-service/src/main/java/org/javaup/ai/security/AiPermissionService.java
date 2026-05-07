package org.javaup.ai.security;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.config.AiSecurityProperties;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class AiPermissionService {

    private final AiSecurityProperties securityProperties;

    public boolean isAdmin(Long userId) {
        if (userId == null || !StringUtils.hasText(securityProperties.getAdminUserIds())) {
            return false;
        }
        String target = String.valueOf(userId);
        return Arrays.stream(securityProperties.getAdminUserIds().split(","))
                .map(String::trim)
                .anyMatch(target::equals);
    }

    public boolean isAdmin(AiUserContext user) {
        return user != null && Boolean.TRUE.equals(user.getAdmin());
    }

    public boolean isCurrentUserAdmin() {
        return isAdmin(AiRequestContextHolder.getRequiredUser());
    }

    public boolean canAccessRoute(AiUserContext user, AssistantRouteType routeType) {
        if (routeType == null) {
            return true;
        }
        return routeType != AssistantRouteType.OPS || isAdmin(user);
    }

    public boolean canAccessSkill(AiUserContext user, AssistantSkillDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        if (!canAccessRoute(user, descriptor.getRouteType())) {
            return false;
        }
        return !Boolean.TRUE.equals(descriptor.getRequiresAdmin()) || isAdmin(user);
    }

    public void requireOpsAccess() {
        if (!isCurrentUserAdmin()) {
            throw new AiAuthorizationException("当前账号无权访问 AI 运维能力，请使用管理员账号访问");
        }
    }
}
