package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class InvalidCursorException extends RuntimeException {

    private final String code;

    public InvalidCursorException(String code, String message) {
        super(message);
        this.code = code;
    }
}
