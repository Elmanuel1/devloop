package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class NotImplementedException extends RuntimeException {
    private final String code;

    public NotImplementedException(String code, String message) {
        super(message);
        this.code = code;
    }
}
