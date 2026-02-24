package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class InvalidETagException extends RuntimeException {

    private final String code;

    public InvalidETagException(String code, String message) {
        super(message);
        this.code = code;
    }
}
