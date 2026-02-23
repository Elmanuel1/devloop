package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class StaleVersionException extends RuntimeException {
    private final String code;

    public StaleVersionException(String code, String message) {
        super(message);
        this.code = code;
    }
}
