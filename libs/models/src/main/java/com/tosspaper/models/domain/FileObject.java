package com.tosspaper.models.domain;

import com.tosspaper.models.utils.EmailUtils;
import com.tosspaper.models.utils.UrlSafeUtils;
import lombok.Builder;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Represents a file object for email attachments.
 * Contains file metadata and content for processing and storage.
 */
@Data
@Builder(toBuilder = true)
public class FileObject {
    
    /** Original file name */
    private final String fileName;
    
    /** Assigned unique identifier for this file (providerMessageId_uuid) */
    private final String assignedId;
    
    /** Storage key for this file (e.g., file path or S3 key) */
    private final String key;

    private final String localFilePath;
    
    /** MIME content type */
    private final String contentType;
    
    /** File content as byte array */
    private final byte[] content;
    
    /** File size in bytes */
    private final long sizeBytes;
    
    /** SHA-256 checksum of the file content */
    private final String checksum;
    
    /** Optional file description or alt text */
    private final String description;
    
    /** Content ID for referencing attachments (e.g., embedded images with cid: URLs) */
    private final String contentId;
    
    /** Additional metadata for the file (e.g., email sender/recipient, custom properties) */
    private final Map<String, String> metadata;
    
    /**
     * Get file content as InputStream for upload operations.
     * 
     * @return InputStream of file content
     */
    public InputStream getContentStream() {
        return new ByteArrayInputStream(content);
    }
    
    /**
     * Get actual file size from content array.
     * 
     * @return actual size in bytes
     */
    public long getActualSize() {
        return content != null ? content.length : 0;
    }
    
    /**
     * Check if file has content.
     * 
     * @return true if content is not null and not empty
     */
    public boolean hasContent() {
        return content != null && content.length > 0;
    }
    
    /**
     * Get file extension from filename.
     * 
     * @return file extension (without dot) or empty string if none
     */
    public String getFileExtension() {
        return com.tosspaper.models.utils.FileExtensionUtils.getFileExtension(fileName);
    }
    
    /**
     * Generate a file key for storage using provided email addresses and assigned ID.
     * Format: {to-email}/{from-email}/{assignedId}-{filename}
     * 
     * @param toEmail the recipient email address
     * @param fromEmail the sender email address
     * @return new FileObject with the generated key
     * @throws IllegalArgumentException if email addresses are null or blank
     */
    public FileObject withGeneratedKey(String toEmail, String fromEmail) {
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("to-email is required for key generation");
        }
        
        if (fromEmail == null || fromEmail.isBlank()) {
            throw new IllegalArgumentException("from-email is required for key generation");
        }
        
        // Extract just the email address from toEmail (may contain display name like "Name" <email@domain.com>)
        String cleanToEmail = EmailUtils.cleanEmailAddress(toEmail);
        
        String generatedKey = String.format("%s/%s/%s-%s", cleanToEmail, fromEmail, assignedId, fileName);
        
        // Make the entire key URL-safe
        String safeKey = UrlSafeUtils.makeUrlSafe(generatedKey);
        
        return this.toBuilder()
            .key(safeKey)
            .build();
    }
    
        /**
         * Create a FileObject for an email attachment.
         * 
         * @param fileName the file name
         * @param contentType the MIME type
         * @param content the file content
         * @param assignedId the assigned unique identifier
         * @return FileObject instance
         */
        public static FileObject emailAttachment(String fileName, String contentType, byte[] content, String assignedId) {
            return FileObject.builder()
                    .fileName(fileName)
                    .assignedId(assignedId)
                    .contentType(contentType)
                    .content(content)
                    .sizeBytes(content != null ? content.length : 0)
                    .build();
        }
    
        /**
         * Create a FileObject with metadata.
         * 
         * @param fileName the file name
         * @param contentType the MIME type
         * @param content the file content
         * @param assignedId the assigned unique identifier
         * @param metadata the metadata map
         * @return FileObject instance
         */
        public static FileObject metadata(String fileName, String contentType, byte[] content, String assignedId, Map<String, String> metadata) {
            return FileObject.builder()
                    .fileName(fileName)
                    .assignedId(assignedId)
                    .contentType(contentType)
                    .content(content)
                    .sizeBytes(content != null ? content.length : 0)
                    .metadata(metadata)
                    .build();
        }
    
    /**
     * Create a FileObject with content ID for referencing (e.g., embedded images).
     * 
     * @param fileName the file name
     * @param contentType the MIME type
     * @param content the file content
     * @param contentId the content ID for referencing
     * @return FileObject instance
     */
    public static FileObject withContentId(String fileName, String contentType, byte[] content, String contentId) {
        return FileObject.builder()
                .fileName(fileName)
                .contentType(contentType)
                .content(content)
                .sizeBytes(content != null ? content.length : 0)
                .contentId(contentId)
                .build();
    }
    
    /**
     * Create a FileObject with all properties.
     * 
     * @param fileName the file name
     * @param contentType the MIME type
     * @param content the file content
     * @param description optional description
     * @param contentId optional content ID
     * @param metadata optional metadata map
     * @return FileObject instance
     */
    public static FileObject create(String fileName, String contentType, byte[] content, 
                                   String description, String contentId, Map<String, String> metadata) {
        return FileObject.builder()
                .fileName(fileName)
                .contentType(contentType)
                .content(content)
                .sizeBytes(content != null ? content.length : 0)
                .description(description)
                .contentId(contentId)
                .metadata(metadata)
                .build();
    }
}
