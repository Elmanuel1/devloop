package com.tosspaper.api;

import com.tosspaper.generated.api.ReceivedMessagesApi;
import com.tosspaper.generated.model.ReceivedMessageList;
import com.tosspaper.generated.model.AttachmentList;
import com.tosspaper.models.query.ReceivedMessageQuery;
import com.tosspaper.models.service.ReceivedMessageService;
import com.tosspaper.mapper.AttachmentMapper;
import com.tosspaper.mapper.ReceivedMessageMapper;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.company.CompanyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ReceivedMessageController implements ReceivedMessagesApi {
    
    private final ReceivedMessageService receivedMessageService;
    private final ReceivedMessageMapper receivedMessageMapper;
    private final AttachmentMapper attachmentMapper;
    private final CompanyService companyService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<ReceivedMessageList> listReceivedMessages(
            String xContextId,
            Integer page,
            Integer pageSize,
            String status,
            String search,
            OffsetDateTime createdDateFrom,
            OffsetDateTime createdDateTo,
            String fromEmail
    ) {
        log.debug("GET /api/v1/received_messages - page={}, pageSize={}, status={}, search={}, createdDateFrom={}, createdDateTo={}, fromEmail={}", 
                page, pageSize, status, search, createdDateFrom, createdDateTo, fromEmail);
        
        String assignedEmail = getAssignedEmailForCompany(HeaderUtils.parseCompanyId(xContextId));
        
        var query = ReceivedMessageQuery.builder()
                .page(page != null ? page : 1)
                .pageSize(pageSize != null ? pageSize : 20)
                .status(status)
                .search(search)
                .createdDateFrom(createdDateFrom)
                .createdDateTo(createdDateTo)
                .assignedEmail(assignedEmail)
                .fromEmail(fromEmail)
                .build();
        
        var result = receivedMessageService.listReceivedMessages(query);
        var apiResponse = receivedMessageMapper.toApiReceivedMessageList(result);
        return ResponseEntity.ok(apiResponse);
    }
    
    private String getAssignedEmailForCompany(Long companyId) {
        var company = companyService.getCompanyById(companyId);
        String assignedEmail = company.getAssignedEmail();
        log.debug("Company {} assigned email: {}", companyId, assignedEmail);
        return assignedEmail;
    }
    
    @Override
    // TODO: Security issue - missing xContextId parameter for permission check
    public ResponseEntity<AttachmentList> getAttachments(UUID messageId) {
        log.debug("GET /api/v1/received_messages/{}/attachments", messageId);

        var attachments = receivedMessageService.getAttachmentsByMessageId(messageId);
        var apiResponse = attachmentMapper.toApiAttachmentList(attachments);
        return ResponseEntity.ok(apiResponse);
    }

}
