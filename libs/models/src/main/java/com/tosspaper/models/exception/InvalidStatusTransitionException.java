package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class InvalidStatusTransitionException extends RuntimeException {
    private final String code;

    public InvalidStatusTransitionException(String code, String message) {
        super(message);
        this.code = code;
    }
}
