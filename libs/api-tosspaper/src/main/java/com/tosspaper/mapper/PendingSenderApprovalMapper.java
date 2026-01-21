package com.tosspaper.mapper;

import com.tosspaper.generated.model.PendingApprovalDocument;
import com.tosspaper.generated.model.PendingSenderApproval;
import com.tosspaper.generated.model.PendingSenderApprovalList;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PendingSenderApprovalMapper {

    public PendingSenderApprovalList toApiResponse(List<com.tosspaper.models.domain.PendingSenderApproval> domainList) {
        List<PendingSenderApproval> apiList = domainList.stream()
                .map(this::toApiPendingSenderApproval)
                .toList();
        
        return new PendingSenderApprovalList().data(apiList);
    }
    
    private PendingSenderApproval toApiPendingSenderApproval(com.tosspaper.models.domain.PendingSenderApproval domain) {
        List<PendingApprovalDocument> attachments = domain.getAttachments().stream()
                .map(this::toApiPendingApprovalDocument)
                .toList();
        
        return new PendingSenderApproval()
                .senderIdentifier(domain.getSenderIdentifier())
                .documentsPending(domain.getDocumentsPending())
                .domainAccessAllowed(domain.getDomainAccessAllowed())
                .attachments(attachments);
    }
    
    private PendingApprovalDocument toApiPendingApprovalDocument(com.tosspaper.models.domain.PendingApprovalDocument domain) {
        return new PendingApprovalDocument()
                .attachmentId(domain.getAttachmentId())
                .filename(domain.getFilename())
                .storageKey(domain.getStorageKey())
                .messageId(domain.getMessageId())
                .dateReceived(domain.getDateReceived());
    }
}

