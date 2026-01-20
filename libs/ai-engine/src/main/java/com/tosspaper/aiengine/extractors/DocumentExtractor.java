package com.tosspaper.aiengine.extractors;

/**
 * Strategy interface for extracting and filtering data from AI provider responses.
 * Different AI providers may have different response formats and fields to filter.
 */
public interface DocumentExtractor {
    
    /**
     * Extract and filter the extraction result from the raw response JSON.
     * Parses the provider-specific response format and removes unwanted fields 
     * (e.g., citations, metadata, etc.).
     *
     * @param rawResponse the raw JSON response from the AI provider
     * @return filtered extraction result as JSON string, or null if parsing fails
     */
    String extract(String rawResponse);
}

