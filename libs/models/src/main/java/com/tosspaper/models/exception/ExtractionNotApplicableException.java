package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class ExtractionNotApplicableException extends RuntimeException {
    private final String code;

    public ExtractionNotApplicableException(String code, String message) {
        super(message);
        this.code = code;
    }
}
