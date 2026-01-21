package com.tosspaper.api;

import com.tosspaper.common.security.SecurityUtils;
import com.tosspaper.generated.api.SenderApprovalsApi;
import com.tosspaper.generated.model.ApproveSenderRequest;
import com.tosspaper.generated.model.ApprovedSenderResponse;
import com.tosspaper.generated.model.PendingSenderApprovalList;
import com.tosspaper.models.enums.EmailApprovalStatus;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.service.EmailApprovalService;
import com.tosspaper.models.service.ApprovedSendersManagementService;
import com.tosspaper.mapper.ApprovedSenderMapper;
import com.tosspaper.mapper.PendingSenderApprovalMapper;
import com.tosspaper.common.HeaderUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SenderApprovalsController implements SenderApprovalsApi {
    
    private final EmailApprovalService emailApprovalService;
    private final ApprovedSendersManagementService approvedSendersManagementService;
    private final ApprovedSenderMapper approvedSenderMapper;
    private final PendingSenderApprovalMapper pendingSenderApprovalMapper;
    
    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'senders:approve')")
    public ResponseEntity<Void> approveSender(String xContextId, ApproveSenderRequest approveSenderRequest) {
        // Get company ID from X-Context-Id header
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String userId = SecurityUtils.getSubjectFromJwt();

        // Convert API approval status enum to domain EmailApprovalStatus
        EmailApprovalStatus approvalStatus = EmailApprovalStatus.fromString(
                approveSenderRequest.getApprovalStatus().getValue()
        );

        // Get sender identifier (email or domain)
        String senderIdentifier = approveSenderRequest.getSenderIdentifier();

        // Approve/reject sender and start extraction (auto-detects email vs domain)
        emailApprovalService.approveSender(companyId, senderIdentifier, approvalStatus, userId);

        return ResponseEntity.ok().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'senders:view')")
    public ResponseEntity<com.tosspaper.generated.model.ApprovedSenderList> listApprovedSenders(
            String xContextId,
            String status,
            Integer page,
            Integer pageSize) {
        log.debug("GET /api/v1/sender-approvals?status={}&page={}&pageSize={}", status, page, pageSize);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        
        // Default to approved if status not provided
        SenderApprovalStatus senderStatus = status != null 
            ? SenderApprovalStatus.fromValue(status) 
            : SenderApprovalStatus.APPROVED;
        
        // Default pagination values
        int effectivePage = page != null ? page : 1;
        int effectivePageSize = pageSize != null ? pageSize : 20;
        
        var paginatedSenders = approvedSendersManagementService.listApprovedSenders(
            companyId, 
            senderStatus, 
            effectivePage, 
            effectivePageSize
        );
        
        // Map to API response
        List<ApprovedSenderResponse> senderResponses = paginatedSenders.data().stream()
                .map(approvedSenderMapper::toApiResponse)
                .toList();
        
        com.tosspaper.generated.model.Pagination apiPagination = new com.tosspaper.generated.model.Pagination()
                .page(paginatedSenders.pagination().page())
                .pageSize(paginatedSenders.pagination().pageSize())
                .totalItems(paginatedSenders.pagination().totalItems())
                .totalPages(paginatedSenders.pagination().totalPages());
        
        com.tosspaper.generated.model.ApprovedSenderList response = new com.tosspaper.generated.model.ApprovedSenderList()
                .data(senderResponses)
                .pagination(apiPagination);
        
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'senders:view')")
    public ResponseEntity<PendingSenderApprovalList> listPendingDocuments(String xContextId) {
        log.debug("GET /api/v1/sender-approvals/pending-documents");
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        
        // Get pending documents grouped by sender
        var pendingDocuments = approvedSendersManagementService.listPendingDocuments(companyId);
        
        // Map to API response
        PendingSenderApprovalList response = pendingSenderApprovalMapper.toApiResponse(pendingDocuments);
        
        return ResponseEntity.ok(response);
    }

}

