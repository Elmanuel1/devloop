package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class IfMatchRequiredException extends RuntimeException {
    private final String code;

    public IfMatchRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }
}
