package com.tosspaper.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ValidAssignedEmailDomainValidator implements ConstraintValidator<ValidAssignedEmailDomain, String> {

    @Value("${app.email.allowed-domain:useassetiq.com}")
    private String allowedDomain;

    @Override
    public void initialize(ValidAssignedEmailDomain constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        // Null or empty is valid (use @NotNull/@NotBlank for required validation)
        if (email == null || email.trim().isEmpty()) {
            return true;
        }

        String trimmedEmail = email.trim();

        // Basic email format check (single '@', non-empty local part and domain)
        int atIndex = trimmedEmail.indexOf('@');
        if (atIndex <= 0 || atIndex != trimmedEmail.lastIndexOf('@')) {
            return false;
        }

        String domain = trimmedEmail.substring(atIndex + 1).toLowerCase();
        if (domain.isBlank()) {
            return false;
        }

        // Validate assigned email domain
        return domain.equals(allowedDomain.toLowerCase());
    }
}

