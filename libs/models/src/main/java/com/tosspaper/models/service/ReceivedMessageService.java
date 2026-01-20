package com.tosspaper.models.service;

import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.query.ReceivedMessageQuery;

import java.util.List;
import java.util.UUID;

public interface ReceivedMessageService {
    
    /**
     * List received messages with optional date range filtering
     */
    Paginated<EmailMessage> listReceivedMessages(ReceivedMessageQuery query);
    
    /**
     * Get attachments for a specific message
     */
    List<EmailAttachment> getAttachmentsByMessageId(UUID messageId);
}
