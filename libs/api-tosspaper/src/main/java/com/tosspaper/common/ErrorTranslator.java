package com.tosspaper.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

public class ErrorTranslator {

    public static ApiError from(BindingResult errors) {
        return new ApiError("api.validation.error", getValidationMessage(errors.getFieldError()));
    }

    private static String getValidationMessage(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            String property = fieldError.getField();
            Object invalidValue = fieldError.getRejectedValue();
            String message = fieldError.getDefaultMessage();
            return "%s: %s. Rejected value:  %s".formatted(property, message, invalidValue);
        }
        return "Your request is invalid. Please check your request and try again";
    }

    public static ApiError from(HttpMessageNotReadableException ex) {
        // Check if the root cause is an enum parsing error using Jackson's exception hierarchy
        Throwable cause = ex.getCause();
        if (cause instanceof JsonMappingException jsonEx) {
            return handleJsonMappingException(jsonEx);
        }
        
        return new ApiError(
                "api.validation.error", "Could not parse your request. Please modify your request and try again");
    }
    
    private static ApiError handleJsonMappingException(JsonMappingException ex) {
        // Handle InvalidFormatException (most common for enum parsing errors)
        if (ex instanceof InvalidFormatException invalidEx) {
            return handleInvalidFormatException(invalidEx);
        }
        
        // Handle ValueInstantiationException (another common enum error)
        if (ex instanceof ValueInstantiationException valueEx) {
            return handleValueInstantiationException(valueEx);
        }
        
        // Generic JSON mapping error
        return new ApiError(
                "api.validation.error", "Invalid value provided. Please check your request and try again");
    }
    
    private static ApiError handleInvalidFormatException(InvalidFormatException ex) {
        Object invalidValue = ex.getValue();
        Class<?> targetType = ex.getTargetType();
        
        // Check if it's an enum type
        if (targetType != null && targetType.isEnum()) {
            String fieldName = getFieldName(ex);
            String enumValues = getEnumValues(targetType);
            
            return new ApiError("api.validation.error", 
                String.format("Invalid value '%s' for field '%s'. Valid values are: %s", 
                    invalidValue, fieldName, enumValues));
        }
        
        // Generic invalid format error
        return new ApiError("api.validation.error", 
            String.format("Invalid value '%s'. Please provide a valid value", invalidValue));
    }
    
    private static ApiError handleValueInstantiationException(ValueInstantiationException ex) {
        Class<?> targetType = ex.getType().getRawClass();
        
        if (targetType != null && targetType.isEnum()) {
            String fieldName = getFieldName(ex);
            String enumValues = getEnumValues(targetType);
            
            return new ApiError("api.validation.error", 
                String.format("Invalid value for field '%s'. Valid values are: %s", 
                    fieldName, enumValues));
        }
        
        return new ApiError("api.validation.error", "Invalid value provided. Please check your request");
    }
    
    private static String getFieldName(JsonMappingException ex) {
        if (ex.getPath() != null && !ex.getPath().isEmpty()) {
            JsonMappingException.Reference ref = ex.getPath().get(ex.getPath().size() - 1);
            return ref.getFieldName() != null ? ref.getFieldName() : "unknown field";
        }
        return "unknown field";
    }
    
    private static String getEnumValues(Class<?> enumClass) {
        if (enumClass.isEnum()) {
            Object[] constants = enumClass.getEnumConstants();
            if (constants != null && constants.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(constants[i].toString().toLowerCase());
                }
                return sb.toString();
            }
        }
        return "see documentation";
    }
}
