package com.tosspaper.models.utils;

import com.tosspaper.models.domain.FileObject;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Utility class for sanitizing filenames by replacing forbidden characters.
 */
@RequiredArgsConstructor
public class FileNameSanitizer {

    private final Map<String, String> replacementMap;
    
    /**
     * Sanitizes a filename by replacing forbidden characters with their replacements.
     * 
     * @param fileName the original filename
     * @return sanitized filename with forbidden characters replaced
     */
    public String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return fileName;
        }
        
        String sanitized = fileName;
        for (Map.Entry<String, String> entry : replacementMap.entrySet()) {
            sanitized = sanitized.replace(entry.getKey(), entry.getValue());
        }
        return sanitized.trim();
    }
    
    /**
     * Creates a new FileObject with sanitized filename.
     * 
     * @param originalFileObject the original file object
     * @return new FileObject with sanitized filename
     */
    public FileObject sanitizeFileObject(FileObject originalFileObject) {
        String sanitizedFileName = sanitize(originalFileObject.getFileName());
        
        return originalFileObject.toBuilder()
            .fileName(sanitizedFileName)
            .build();
    }
}
