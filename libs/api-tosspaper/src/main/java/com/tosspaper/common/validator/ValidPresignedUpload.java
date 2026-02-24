package com.tosspaper.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a PresignedUrlRequest against tender file upload rules:
 * allowed content types, max file size, and extension-to-content-type match.
 */
@Constraint(validatedBy = ValidPresignedUploadValidator.class)
@Target({ ElementType.TYPE, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPresignedUpload {
    String message() default "Invalid upload request";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
