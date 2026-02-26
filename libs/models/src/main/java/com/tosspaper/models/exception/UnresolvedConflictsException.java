package com.tosspaper.models.exception;

import lombok.Getter;

@Getter
public class UnresolvedConflictsException extends RuntimeException {
    private final String code;

    public UnresolvedConflictsException(String code, String message) {
        super(message);
        this.code = code;
    }
}
