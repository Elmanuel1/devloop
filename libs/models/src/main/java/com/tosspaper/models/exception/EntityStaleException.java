package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class EntityStaleException extends RuntimeException {
    private final String code;

    public EntityStaleException(String code, String message) {
        super(message);
        this.code = code;
    }
}
