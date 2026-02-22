package com.tosspaper.precon;

import lombok.Getter;

@Getter
public class StaleVersionException extends RuntimeException {
    private final String code;

    public StaleVersionException(String code, String message) {
        super(message);
        this.code = code;
    }
}
