package com.tosspaper.emailengine.service.impl;

import com.tosspaper.models.query.ReceivedMessageQuery;
import com.tosspaper.emailengine.repository.EmailAttachmentRepository;
import com.tosspaper.emailengine.repository.EmailMessageRepository;
import com.tosspaper.models.service.ReceivedMessageService;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.paging.Paginated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedMessageServiceImpl implements ReceivedMessageService {
    
    private final EmailMessageRepository emailMessageRepository;
    private final EmailAttachmentRepository emailAttachmentRepository;
    
    @Override
    public Paginated<EmailMessage> listReceivedMessages(ReceivedMessageQuery query) {
        log.debug("Listing received messages with query: {}", query);
        return emailMessageRepository.findByQuery(query);
    }
    
    @Override
    public List<EmailAttachment> getAttachmentsByMessageId(UUID messageId) {
        log.debug("Getting attachments for message: {}", messageId);

        var attachments = emailAttachmentRepository.findByMessageId(messageId);

        log.debug("Retrieved {} attachments for message {}", attachments.size(), messageId);
        return attachments;
    }

    @Override
    public Optional<EmailAttachment> getAttachmentByStorageKey(String storageKey, Long companyId) {
        log.debug("Getting attachment by storageKey: {} for company: {}", storageKey, companyId);

        return emailAttachmentRepository.findByStorageKeyAndCompanyId(storageKey, companyId);
    }
}
