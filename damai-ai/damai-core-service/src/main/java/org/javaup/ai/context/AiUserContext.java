package org.javaup.ai.context;

import lombok.Builder;
import lombok.Data;

/**
 * 当前请求对应的登录用户上下文。
 */
@Data
@Builder
public class AiUserContext {

    private Long userId;

    private String token;

    private String mobile;

    private String name;

    private String email;

    private Boolean admin;
}
