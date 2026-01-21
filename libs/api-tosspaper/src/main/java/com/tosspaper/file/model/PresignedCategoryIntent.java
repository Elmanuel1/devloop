package com.tosspaper.file.model;

import java.util.Arrays;

import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

/**
 * Defines different categories of presigned URL intents with their configuration
 * Maps to the generated PresignedUrlIntent enum values
 */
public enum PresignedCategoryIntent {
    
    COMPANY_LOGO("company_logo", "PT15M", ObjectCannedACL.PUBLIC_READ),
    INVOICE("invoice", "PT30M", ObjectCannedACL.PRIVATE),
    DELIVERY_SLIP("delivery_slip", "PT30M", ObjectCannedACL.PRIVATE),
    PROPERTY_IMAGE("property_image", "PT15M", ObjectCannedACL.PUBLIC_READ),
    PROFILE_PICTURE("profile_picture", "PT15M", ObjectCannedACL.PUBLIC_READ);
    
    /**
     * Convert from generated PresignedUrlIntent to PresignedCategoryIntent
     */
    public static PresignedCategoryIntent fromPresignedUrlIntent(com.tosspaper.generated.model.PresignedUrlIntent intent) {
        return  Arrays.stream(PresignedCategoryIntent.values())
            .filter(i -> i.value.equals(intent.getValue()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Invalid intent: " + intent));
    }
    
    private final String value;
    private final String duration;
    private final ObjectCannedACL objectCannedACL;
    
    PresignedCategoryIntent(String value, String duration, ObjectCannedACL objectCannedACL) {
        this.value = value;
        this.duration = duration;
        this.objectCannedACL = objectCannedACL;
    }
    
    public String getValue() {
        return value;
    }
    
    public String getDuration() {
        return duration;
    }
    
    public ObjectCannedACL getObjectCannedACL() {
        return objectCannedACL;
    }
} 