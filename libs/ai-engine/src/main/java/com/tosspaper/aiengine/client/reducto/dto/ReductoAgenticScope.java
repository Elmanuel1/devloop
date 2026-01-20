package com.tosspaper.aiengine.client.reducto.dto;

/**
 * Scope values for Reducto agentic enhancement.
 */
public enum ReductoAgenticScope {
    TEXT("text");
    
    private final String value;
    
    ReductoAgenticScope(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return value;
    }
}
