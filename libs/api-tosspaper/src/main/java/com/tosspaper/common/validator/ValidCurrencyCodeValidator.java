package com.tosspaper.common.validator;

import com.tosspaper.models.domain.Currency;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link ValidCurrencyCode} annotation.
 * Validates that the currency code exists in the {@link Currency} enum.
 */
public class ValidCurrencyCodeValidator implements ConstraintValidator<ValidCurrencyCode, String> {

    @Override
    public void initialize(ValidCurrencyCode constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null values are valid - use @NotNull/@NotBlank for nullability validation
        if (value == null || value.isBlank()) {
            return true;
        }

        // Check if currency code is supported
        Currency currency = Currency.fromCode(value);
        return currency != null;
    }
}
