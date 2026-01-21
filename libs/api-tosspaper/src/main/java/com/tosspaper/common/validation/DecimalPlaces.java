package com.tosspaper.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = DecimalPlacesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(DecimalPlaces.List.class)
public @interface DecimalPlaces {
    String message() default "Value must have at most {value} decimal places";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    int value() default 2;

    /**
     * Container annotation for repeatable {@link DecimalPlaces} constraints.
     */
    @Documented
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        DecimalPlaces[] value();
    }
} 