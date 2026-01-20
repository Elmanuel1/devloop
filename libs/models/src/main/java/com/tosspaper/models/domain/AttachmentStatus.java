package com.tosspaper.models.domain;

/**
 * Represents the upload status of an email attachment.
 */
public enum AttachmentStatus {
    /** File is saved locally and pending upload to S3 */
    pending("pending"),
    
    /** File is currently being processed/uploaded to S3 */
    processing("processing"),
    
    /** File has been successfully uploaded to S3 */
    uploaded("uploaded"),
    
    /** Upload to S3 failed after all retry attempts */
    failed("failed");
    
    private final String value;
    
    AttachmentStatus(String value) {
        this.value = value;
    }
    
    /**
     * Get the string value of the status.
     * 
     * @return the lowercase string value
     */
    public String getValue() {
        return value;
    }
    
    /**
     * Get the enum from a string value.
     * 
     * @param value the string value
     * @return the corresponding enum value
     * @throws IllegalArgumentException if the value is not found
     */
    public static AttachmentStatus fromValue(String value) {
        for (AttachmentStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status value: " + value);
    }
}
