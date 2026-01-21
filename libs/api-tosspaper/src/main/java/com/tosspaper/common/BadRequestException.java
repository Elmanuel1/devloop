package com.tosspaper.common;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode(callSuper = true)
@Data
public class BadRequestException extends RuntimeException {
    private String code;
    public BadRequestException( String code,String message) {
        super(message);
        this.code = code;
    }
} 