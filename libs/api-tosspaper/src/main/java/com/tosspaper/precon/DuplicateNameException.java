package com.tosspaper.precon;

import lombok.Getter;

@Getter
public class DuplicateNameException extends RuntimeException {
    private final String code;

    public DuplicateNameException(String code, String message) {
        super(message);
        this.code = code;
    }
}
