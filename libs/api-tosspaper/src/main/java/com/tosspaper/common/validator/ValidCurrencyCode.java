package com.tosspaper.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string is a valid ISO 4217 currency code supported by the application.
 * See {@link com.tosspaper.models.domain.Currency} for supported currency codes.
 */
@Constraint(validatedBy = ValidCurrencyCodeValidator.class)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrencyCode {
    String message() default "Invalid currency code. Must be a valid ISO 4217 code (e.g., USD, CAD, EUR, AED)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
