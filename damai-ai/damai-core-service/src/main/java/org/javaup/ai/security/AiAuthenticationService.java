package org.javaup.ai.security;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.vo.UserDetailVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import org.javaup.ai.ai.function.call.UserCall;

/**
 * 通过 damai-pro 的用户服务解析登录态。
 */
@Service
public class AiAuthenticationService {

    @Resource
    private UserCall userCall;

    @Resource
    private AiPermissionService aiPermissionService;

    public AiUserContext authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            throw new AiAuthenticationException("登录态缺失");
        }
        UserDetailVo user = userCall.currentUser(token);
        return AiUserContext.builder()
                .userId(user.getId())
                .token(token)
                .mobile(user.getMobile())
                .name(user.getName())
                .email(user.getEmail())
                .admin(aiPermissionService.isAdmin(user.getId()))
                .build();
    }
}
