package com.tosspaper.common;

public class InternalServerErrorException extends RuntimeException {
    public InternalServerErrorException(String message) {
        super(message);
    }

    public InternalServerErrorException() {
    }

    public String getCode() {
        return "internal_server_error";
    }
} 