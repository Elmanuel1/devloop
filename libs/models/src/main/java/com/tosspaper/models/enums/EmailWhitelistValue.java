package com.tosspaper.models.enums;

public enum EmailWhitelistValue {
    EMAIL("email"),
    DOMAIN("domain");

    private final String value;

    EmailWhitelistValue(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EmailWhitelistValue fromString(String value) {
        for (EmailWhitelistValue type : EmailWhitelistValue.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid whitelist value: " + value);
    }
}

