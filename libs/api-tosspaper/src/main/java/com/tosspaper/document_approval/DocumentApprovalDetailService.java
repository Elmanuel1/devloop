package com.tosspaper.document_approval;

import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.service.DocumentApprovalService;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.DocumentApproval;
import com.tosspaper.models.domain.DocumentApprovalDetail;
import com.tosspaper.models.domain.ExtractionResult;
import com.tosspaper.models.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Service for fetching complete document approval details.
 * Combines data from document_approvals, extraction_task, and invoice/delivery_slip tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentApprovalDetailService {

    private final DocumentApprovalService documentApprovalService;
    private final ExtractionTaskRepository extractionTaskRepository;

    /**
     * Fetch complete document approval detail for review page.
     *
     * @param approvalId the document approval ID
     * @return complete approval detail with all related data
     */
    public DocumentApprovalDetail getApprovalDetail(Long companyId, String approvalId) {
        log.debug("Fetching approval detail for approval ID: {}", approvalId);

        // 1. Fetch approval record
        DocumentApproval approval = documentApprovalService.findById(approvalId);
        if (!Objects.equals(approval.getCompanyId(), companyId)) {
            throw new ForbiddenException("You do not have permission to approve this approval record");
        }

        // 4. Build response
        return buildDetail(approval);
    }

    private DocumentApprovalDetail buildDetail(DocumentApproval approval) {
        return DocumentApprovalDetail.builder()
                .approvalId(approval.getId())
                .assignedId(approval.getAssignedId())
                .companyId(approval.getCompanyId())
                .documentType(approval.getDocumentType())
                .externalDocumentNumber(approval.getExternalDocumentNumber())
                .poNumber(approval.getPoNumber())
                .fromEmail(approval.getFromEmail())
                .createdAt(approval.getCreatedAt())
                .poNumber(approval.getPoNumber())
                .projectId(approval.getProjectId())
                .approvedAt(approval.getApprovedAt())
                .rejectedAt(approval.getRejectedAt())
                .reviewedBy(approval.getReviewedBy())
                .reviewNotes(approval.getReviewNotes())
                .documentSummary(approval.getDocumentSummary())
                .storageKey(approval.getStorageKey())
                .build();
    }

    /**
     * Fetch raw extraction results for document approval.
     * This exposes the unformatted extraction data that the user can review and modify
     * before submitting their approval.
     *
     * @param companyId  the company ID for authorization
     * @param approvalId the document approval ID
     * @return extraction result with raw data
     */
    public ExtractionResult getExtractionResult(Long companyId, String approvalId) {
        log.debug("Fetching extraction result for approval ID: {}", approvalId);

        // 1. Fetch approval record and verify company ownership
        DocumentApproval approval = documentApprovalService.findById(approvalId);
        if (!Objects.equals(approval.getCompanyId(), companyId)) {
            throw new ForbiddenException("You do not have permission to access this approval record");
        }

        // 2. Fetch extraction task to get raw results
        ExtractionTask extractionTask = extractionTaskRepository.findByAssignedId(approval.getAssignedId());

        // 3. Build and return result
        return ExtractionResult.builder()
                .assignedId(extractionTask.getAssignedId())
                .fromEmail(extractionTask.getFromAddress())
                .toEmail(extractionTask.getToAddress())
                .documentType(approval.getDocumentType())
                .extractionResult(extractionTask.getExtractTaskResults())
                .storageUrl(extractionTask.getStorageKey())
                .matchType(extractionTask.getMatchType().getValue())
                .matchReport(extractionTask.getMatchReport())
                .poNumber(extractionTask.getPoNumber())
                .projectId(extractionTask.getProjectId())
                .poId(extractionTask.getPurchaseOrderId())
                .build();
    }
}
