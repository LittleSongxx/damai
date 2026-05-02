package org.javaup.ai.security;

import org.javaup.ai.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiSecurityExceptionHandler {

    @ExceptionHandler(AiAuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAuthorizationException(AiAuthorizationException ex) {
        return ApiResponse.error(403, ex.getMessage());
    }
}
