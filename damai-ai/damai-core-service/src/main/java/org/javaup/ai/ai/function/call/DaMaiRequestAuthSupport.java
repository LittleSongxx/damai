package org.javaup.ai.ai.function.call;

import cn.hutool.http.HttpRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DaMaiRequestAuthSupport {

    @Value("${damai.pro.internal-token:}")
    private String internalToken;

    @Value("${damai.pro.allow-unsafe-no-verify-fallback:false}")
    private boolean allowUnsafeNoVerifyFallback;

    public HttpRequest apply(HttpRequest request) {
        if (StringUtils.hasText(internalToken)) {
            return request.header("X-Internal-Token", internalToken);
        }
        if (allowUnsafeNoVerifyFallback) {
            return request.header("no_verify", "true");
        }
        throw new IllegalStateException("damai-pro internal token is required; set damai.pro.internal-token or explicitly enable damai.pro.allow-unsafe-no-verify-fallback for local development");
    }
}
