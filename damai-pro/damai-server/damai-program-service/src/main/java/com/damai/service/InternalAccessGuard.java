package com.damai.service;

import com.damai.enums.BaseCode;
import com.damai.exception.DaMaiFrameException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Component
public class InternalAccessGuard {

    @Value("${internal.access.token:${damai.pro.internal-token:}}")
    private String internalAccessToken;

    public void require(String token) {
        if (!StringUtils.hasText(internalAccessToken) || !Objects.equals(internalAccessToken, token)) {
            throw new DaMaiFrameException(BaseCode.ONLY_SIGNATURE_ACCESS_IS_ALLOWED.getCode(), "AI reservation 仅允许内部调用");
        }
    }
}
