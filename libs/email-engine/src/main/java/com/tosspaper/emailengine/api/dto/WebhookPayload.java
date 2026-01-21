package com.tosspaper.emailengine.api.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Data
@Builder
public class WebhookPayload {
    private String jsonPayload;
    private Map<String, MultipartFile> files;
    
    public static WebhookPayload fromJson(String jsonPayload) {
        return WebhookPayload.builder()
                .jsonPayload(jsonPayload)
                .build();
    }
    
    public static WebhookPayload fromMultipart(String jsonPayload, Map<String, MultipartFile> files) {
        return WebhookPayload.builder()
                .jsonPayload(jsonPayload)
                .files(files)
                .build();
    }
    
    public boolean hasFiles() {
        return files != null && !files.isEmpty();
    }
}
