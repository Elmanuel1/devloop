package com.tosspaper.models.enums;

/**
 * Direction of an email message indicating whether it was received or sent.
 */
public enum MessageDirection {
    /** Message received from external source */
    INCOMING("incoming"),
    
    /** Message sent to external destination */
    OUTGOING("outgoing");
    
    private final String value;
    
    MessageDirection(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    public static MessageDirection fromValue(String value) {
        for (MessageDirection direction : values()) {
            if (direction.value.equals(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown message direction: " + value);
    }
}
