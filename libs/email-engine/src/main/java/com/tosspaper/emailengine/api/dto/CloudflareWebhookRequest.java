package com.tosspaper.emailengine.api.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudflareWebhookRequest {
    
    private EmailMessageDto emailMessage;
    private List<FileObjectDto> attachments;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmailMessageDto {
        private String provider;
        private String providerMessageId;
        private String inReplyTo;
        private String fromAddress;
        private String toAddress;
        private String cc;
        private String bcc;
        private String subject;
        private String bodyText;
        private String bodyHtml;
        private String headers;
        private String direction;
        private String status;
        private String providerTimestamp;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileObjectDto {
        private String fileName;
        private String contentType;
        private String content;
        private Long sizeBytes;
        private String description;
        private String contentId;
    }
}