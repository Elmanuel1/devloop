package com.tosspaper.common;

import static com.tosspaper.common.ApiErrorMessages.UNAUTHORIZED;

public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(Throwable cause) {
        super(UNAUTHORIZED, cause);
    }

    public UnauthorizedException() {
        super(UNAUTHORIZED);
    }

    public String getCode() {
        return "unauthorized_exception";
    }
} 