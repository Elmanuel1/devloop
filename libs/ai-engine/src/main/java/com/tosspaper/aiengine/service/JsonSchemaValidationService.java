package com.tosspaper.aiengine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tosspaper.aiengine.factory.DocumentResourceFactory;
import com.tosspaper.models.domain.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for validating JSON content against JSON schemas.
 * Uses the document resource factory to load schemas and caches them for performance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonSchemaValidationService {
    
    private final ObjectMapper objectMapper;
    private final DocumentResourceFactory resourceFactory;
    private final Map<DocumentType, JsonSchema> schemaCache = new ConcurrentHashMap<>();
    
    /**
     * Validate JSON against schema for the given document type.
     * 
     * @param jsonContent the JSON content to validate
     * @param documentType the document type (determines which schema to use)
     * @return validation result with errors if any
     */
    public ValidationResult validate(String jsonContent, DocumentType documentType) {
        try {
            JsonSchema schema = getSchema(documentType);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            
            Set<ValidationMessage> errors = schema.validate(jsonNode);
            
            if (errors.isEmpty()) {
                log.info("JSON validation passed for document type: {}", documentType);
                return ValidationResult.success();
            } else {
                List<String> errorMessages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
                    
                log.warn("JSON validation failed for document type {}: {}", 
                    documentType, errorMessages);
                    
                return ValidationResult.failure(errorMessages);
            }
            
        } catch (Exception e) {
            log.error("Failed to validate JSON for document type: {}", documentType, e);
            return ValidationResult.failure(List.of("Validation error: " + e.getMessage()));
        }
    }
    
    /**
     * Get or load the JSON schema for the given document type.
     * Schemas are cached after first load for performance.
     */
    private JsonSchema getSchema(DocumentType documentType) {
        return schemaCache.computeIfAbsent(documentType, type -> {
            try {
                // Reuse existing DocumentResourceFactory to load schema
                String schemaJson = resourceFactory.loadSchema(type);
                JsonNode schemaNode = objectMapper.readTree(schemaJson);
                JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
                return factory.getSchema(schemaNode);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load schema for: " + type, e);
            }
        });
    }
    
    /**
     * Result of JSON schema validation.
     */
    @Data
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }
        
        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}

