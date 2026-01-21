package com.tosspaper.common;

import lombok.Getter;

@Getter
public class DuplicateException extends RuntimeException {
    private final String code;
    public DuplicateException(String code, String message) {
        super(message);
        this.code = code;
    }
} 