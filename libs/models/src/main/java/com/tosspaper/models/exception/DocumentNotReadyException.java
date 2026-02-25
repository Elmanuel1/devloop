package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class DocumentNotReadyException extends RuntimeException {
    private final String code;

    public DocumentNotReadyException(String code, String message) {
        super(message);
        this.code = code;
    }
}
