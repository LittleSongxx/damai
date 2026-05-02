package org.javaup.ai.security;

/**
 * AI 接口鉴权失败。
 */
public class AiAuthenticationException extends RuntimeException {

    public AiAuthenticationException(String message) {
        super(message);
    }
}
