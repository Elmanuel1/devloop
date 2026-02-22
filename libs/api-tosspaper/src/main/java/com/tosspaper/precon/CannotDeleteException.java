package com.tosspaper.precon;

import lombok.Getter;

@Getter
public class CannotDeleteException extends RuntimeException {
    private final String code;

    public CannotDeleteException(String code, String message) {
        super(message);
        this.code = code;
    }
}
