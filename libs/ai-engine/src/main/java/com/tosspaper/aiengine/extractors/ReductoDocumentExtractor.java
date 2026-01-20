package com.tosspaper.aiengine.extractors;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reducto-specific implementation of extraction and filtering.
 * Parses Reducto's response format and recursively removes all 'citations' arrays.
 * Only active when ai.provider=reducto.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "reducto")
@RequiredArgsConstructor
public class ReductoDocumentExtractor implements DocumentExtractor {

    private final ObjectMapper objectMapper;

    @Override
    public String extract(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            log.warn("Raw response is null or empty, cannot extract data");
            return null;
        }
        
        try {
            // Parse Reducto response: result.result contains the extraction data
            Map<String, Object> rawData = objectMapper.readValue(rawResponse, Map.class);
            Map<String, Object> resultData = (Map<String, Object>) rawData.get("result");
            
            if (resultData == null) {
                log.warn("No 'result' field in Reducto response");
                return null;
            }
            
            Map<String, Object> extractionData = (Map<String, Object>) resultData.get("result");
            
            if (extractionData == null) {
                log.warn("No extraction data in Reducto response");
                return null;
            }
            
            // Remove citations recursively
            Map<String, Object> cleanedData = removeCitations(extractionData);
            
            // Convert back to JSON string
            return objectMapper.writeValueAsString(cleanedData);
            
        } catch (Exception e) {
            log.error("Failed to extract and filter data from Reducto response: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Recursively remove all 'citations' fields from nested maps.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> removeCitations(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if ("citations".equals(entry.getKey())) {
                continue; // Skip citations field
            }
            result.put(entry.getKey(), removeCitationsFromValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Process individual values, handling nested maps and lists recursively.
     * If a Map has a 'value' key (Reducto structure), extract just the value.
     */
    @SuppressWarnings("unchecked")
    private Object removeCitationsFromValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> mapValue = (Map<String, Object>) value;

            // Check if this is a Reducto value/citations structure
            if (mapValue.containsKey("value")) {
                // Extract just the value, then recursively process it
                return removeCitationsFromValue(mapValue.get("value"));
            }

            // Otherwise, recursively remove citations from the map
            return removeCitations(mapValue);
        } else if (value instanceof List) {
            return ((List<?>) value).stream()
                .map(this::removeCitationsFromValue)
                .collect(Collectors.toList());
        }
        return value;
    }
}

