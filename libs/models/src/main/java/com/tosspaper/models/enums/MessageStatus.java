package com.tosspaper.models.enums;

/**
 * Processing status of an email message in the system.
 */
public enum MessageStatus {
    /** Message has been received but not yet processed */
    RECEIVED("received"),
    
    /** Message is currently being processed */
    PROCESSING("processing"),
    
    /** Message has been successfully processed */
    PROCESSED("processed"),
    
    /** Message processing failed */
    FAILED("failed");
    
    private final String value;
    
    MessageStatus(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static MessageStatus fromValue(String value) {
        for (MessageStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown message status: " + value);
    }
}
