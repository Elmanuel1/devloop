package com.tosspaper.emailengine.service.impl;

import com.tosspaper.emailengine.repository.EmailMessageRepository;
import com.tosspaper.models.domain.EmailMetadata;
import com.tosspaper.models.service.EmailMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementation of EmailMetadataService.
 * Provides email metadata for extraction tasks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailMetadataServiceImpl implements EmailMetadataService {
    
    private final EmailMessageRepository emailMessageRepository;
    
    @Override
    public Optional<EmailMetadata> getEmailMetadataByAttachmentId(String attachmentAssignedId) {
        log.debug("Fetching email metadata for attachment: {}", attachmentAssignedId);
        
        return emailMessageRepository.findByAttachmentId(attachmentAssignedId)
            .map(emailMessage -> EmailMetadata.builder()
                .companyId(emailMessage.getCompanyId())
                .fromAddress(emailMessage.getFromAddress())
                .toAddress(emailMessage.getToAddress())
                .subject(emailMessage.getSubject())
                .receivedAt(emailMessage.getProviderTimestamp())
                .emailMessageId(emailMessage.getId())
                .emailThreadId(emailMessage.getThreadId())
                .build());
    }
}

