package com.tosspaper.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.document_approval.DocumentApprovalDetailService;
import com.tosspaper.document_approval.DocumentApprovalApiService;
import com.tosspaper.common.security.SecurityUtils;
import com.tosspaper.generated.api.DocumentApprovalsApi;
import com.tosspaper.generated.model.DocumentApprovalDetail;
import com.tosspaper.generated.model.DocumentApprovalList;
import com.tosspaper.generated.model.ReviewExtractionRequest;
import com.tosspaper.common.HeaderUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentApprovalsController implements DocumentApprovalsApi {

    private final DocumentApprovalApiService documentApprovalService;
    private final DocumentApprovalDetailService documentApprovalDetailService;
    private final ObjectMapper objectMapper;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'extraction:view')")
    public ResponseEntity<DocumentApprovalList> listDocumentApprovals(
            String xContextId,
            Integer pageSize,
            String cursor,
            String status,
            String documentType,
            String fromEmail,
            OffsetDateTime createdDateFrom,
            OffsetDateTime createdDateTo,
            String projectId) {

        log.debug("GET /v1/document-approvals - pageSize={}, cursor={}, status={}, documentType={}, fromEmail={}, createdFrom={}, createdTo={}, projectId={}",
            pageSize, cursor, status, documentType, fromEmail, createdDateFrom, createdDateTo, projectId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        
        // All logic moved to service
        DocumentApprovalApiService.DocumentApprovalListResponse result = 
            documentApprovalService.listDocumentApprovalsFromApi(
                companyId, pageSize, cursor, status, documentType, fromEmail,
                createdDateFrom, createdDateTo, projectId
            );
        
        // Convert to API model using mapper
        DocumentApprovalList apiList = DocumentApprovalMapper.toApiListWithPagination(result);
        
        return ResponseEntity.ok(apiList);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'extraction:approve')")
    public ResponseEntity<Void> reviewExtraction(
            String xContextId,
            String approvalId,
            ReviewExtractionRequest reviewExtractionRequest) {

        log.debug("POST /v1/document-approvals/{}/reviews - approved={}, hasDocumentData={}",
            approvalId, reviewExtractionRequest.getApproved(), reviewExtractionRequest.getDocumentData() != null);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String userId = SecurityUtils.getSubjectFromJwt();

        // Convert Map to Extraction if documentData is present
        com.tosspaper.models.extraction.dto.Extraction extraction = null;
        if (reviewExtractionRequest.getDocumentData() != null) {
            extraction = objectMapper.convertValue(
                reviewExtractionRequest.getDocumentData(),
                com.tosspaper.models.extraction.dto.Extraction.class
            );
        }

        documentApprovalService.reviewExtraction(
            companyId,
            approvalId,
            reviewExtractionRequest.getApproved(),
            userId,
            reviewExtractionRequest.getNotes(),
            extraction
        );

        log.info("Successfully reviewed approval {}: {}", approvalId, reviewExtractionRequest.getApproved() ? "APPROVED" : "REJECTED");
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'extraction:view')")
    public ResponseEntity<DocumentApprovalDetail> getDocumentApprovalDetail(String xContextId, String approvalId) {
        log.debug("GET /v1/document-approvals/{}", approvalId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        com.tosspaper.models.domain.DocumentApprovalDetail detail =
            documentApprovalDetailService.getApprovalDetail(companyId, approvalId);

        DocumentApprovalDetail apiDetail = DocumentApprovalMapper.toDetailApi(detail);

        return ResponseEntity.ok(apiDetail);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'extraction:view')")
    public ResponseEntity<com.tosspaper.generated.model.ExtractionResultResponse> getDocumentApprovalExtraction(
            String xContextId,
            String approvalId) {

        log.debug("GET /v1/document-approvals/{}/extraction", approvalId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        com.tosspaper.models.domain.ExtractionResult result =
            documentApprovalDetailService.getExtractionResult(companyId, approvalId);

        com.tosspaper.generated.model.ExtractionResultResponse response =
            DocumentApprovalMapper.toExtractionResultApi(result);

        return ResponseEntity.ok(response);
    }
}

