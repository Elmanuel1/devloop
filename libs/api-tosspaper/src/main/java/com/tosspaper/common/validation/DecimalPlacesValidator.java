package com.tosspaper.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

public class DecimalPlacesValidator implements ConstraintValidator<DecimalPlaces, BigDecimal> {
    
    private int maxDecimalPlaces;
    
    @Override
    public void initialize(DecimalPlaces constraintAnnotation) {
        this.maxDecimalPlaces = constraintAnnotation.value();
    }
    
    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Let @NotNull handle null validation if needed
        }
        
        // Get the scale (number of decimal places)
        int scale = value.scale();
        
        // If scale is negative, it means the number is an integer with trailing zeros
        // e.g., 1200 has scale of -2, but we consider it as 0 decimal places
        if (scale < 0) {
            return true;
        }
        
        return scale <= maxDecimalPlaces;
    }
} 