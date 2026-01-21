package com.tosspaper.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.BeanWrapperImpl;

public class AtLeastOneNotNullValidator implements ConstraintValidator<AtLeastOneNotNull, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return Arrays.stream(new BeanWrapperImpl(value).getPropertyDescriptors())
                .filter(pd -> !"class".equals(pd.getName()))
                .map(pd -> new BeanWrapperImpl(value).getPropertyValue(pd.getName()))
                .anyMatch(Objects::nonNull);
    }
} 