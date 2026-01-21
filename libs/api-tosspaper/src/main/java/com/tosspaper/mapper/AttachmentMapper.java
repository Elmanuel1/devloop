package com.tosspaper.mapper;

import com.tosspaper.generated.model.Attachment;
import com.tosspaper.generated.model.AttachmentList;
import com.tosspaper.models.domain.EmailAttachment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AttachmentMapper {

    public AttachmentList toApiAttachmentList(List<EmailAttachment> attachments) {
        if (attachments == null) {
            return null;
        }

        List<Attachment> apiAttachments = attachments.stream()
                .map(this::toApiAttachment)
                .toList();

        var result = new AttachmentList();
        result.setData(apiAttachments);
        return result;
    }

    public Attachment toApiAttachment(EmailAttachment record) {
        if (record == null) {
            return null;
        }

        var attachment = new Attachment();
        attachment.setId(record.getAssignedId());
        attachment.setFileName(record.getFileName());
        attachment.setFileSize(record.getSizeBytes());
        attachment.setStatus(record.getStatus().getValue());
        attachment.setContentType(record.getContentType());
        // Return just the storage key, not the full URL
        // Frontend will use this key to request a presigned download URL
        attachment.setStorageUrl(record.getStorageUrl());
        return attachment;
    }
}
