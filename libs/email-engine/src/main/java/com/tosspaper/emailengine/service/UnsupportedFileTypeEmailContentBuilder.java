package com.tosspaper.emailengine.service;

import com.tosspaper.models.properties.FileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Builder for unsupported file type notification email content.
 * Used when attachments have unsupported file types.
 */
@Component
@RequiredArgsConstructor
public class UnsupportedFileTypeEmailContentBuilder {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");
    private final FileProperties fileProperties;
    
    /**
     * Build email subject for unsupported file type notification.
     */
    public String buildSubject() {
        return "Unsupported File Type";
    }
    
    /**
     * Build email body for unsupported file type notification.
     */
    public String buildBody(String senderEmail, String toAddress, List<com.tosspaper.models.domain.FileObject> invalidFiles, java.time.OffsetDateTime receivedAt) {
        String receivedDateStr = receivedAt.format(DATE_FORMATTER);
        
        // Build list of invalid files
        StringBuilder filesList = new StringBuilder();
        for (int i = 0; i < invalidFiles.size(); i++) {
            com.tosspaper.models.domain.FileObject file = invalidFiles.get(i);
            String fileName = file.getFileName() != null ? file.getFileName() : "unknown";
            String fileType = file.getContentType() != null ? file.getContentType() : "unknown";
            filesList.append("  - ").append(fileName).append(" (").append(fileType).append(")");
            if (i < invalidFiles.size() - 1) {
                filesList.append("\n");
            }
        }
        
        String fileCountText = invalidFiles.size() == 1 ? "file" : "files";
        Set<String> allowedTypes = fileProperties.getAllowedContentTypes();
        
        return String.format(
            "Hello,\n\n" +
            "We received your email, but %d attached %s %s unsupported file type(s).\n\n" +
            "Details:\n" +
            "- Received: %s\n" +
            "- Unsupported files:\n" +
            "%s\n\n" +
            "Please send your files in a supported format (e.g., %s) to %s.\n\n" +
            "Best regards,\n" +
            "TossPaper Team",
            invalidFiles.size(),
            fileCountText,
            invalidFiles.size() == 1 ? "has an" : "have",
            receivedDateStr,
            filesList.toString(),
            String.join(", ", allowedTypes),
            toAddress
        );
    }
}

