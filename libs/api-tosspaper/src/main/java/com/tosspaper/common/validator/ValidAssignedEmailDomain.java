package com.tosspaper.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that an email address belongs to the useassetiq.com domain.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAssignedEmailDomainValidator.class)
@Documented
public @interface ValidAssignedEmailDomain {
    String message() default "Email must be from the authorized email domain";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

