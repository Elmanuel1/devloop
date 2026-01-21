package com.tosspaper.common;

public class EmailVerificationRequiredException extends RuntimeException {
    public EmailVerificationRequiredException(Throwable cause) {
        super(ApiErrorMessages.EMAIL_VERIFICATION_REQUIRED, cause);
    }

    public String getCode() {
        return "email_verification_required";
    }
} 