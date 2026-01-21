package com.tosspaper.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.*;
import com.tosspaper.document_approval.DocumentApprovalApiService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class DocumentApprovalMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static DocumentApproval toApi(com.tosspaper.models.domain.DocumentApproval domain) {
        if (domain == null) {
            return null;
        }
        
        DocumentApproval api = new DocumentApproval();
        api.setId(domain.getId());
        api.setAssignedId(domain.getAssignedId());
        api.setCompanyId(domain.getCompanyId());
        api.setFromEmail(domain.getFromEmail());
        api.setDocumentType(domain.getDocumentType());
        api.setProjectId(domain.getProjectId());
        api.setApprovedAt(domain.getApprovedAt());
        api.setRejectedAt(domain.getRejectedAt());
        api.setReviewedBy(domain.getReviewedBy());
        api.setReviewNotes(domain.getReviewNotes());
        api.setDocumentSummary(domain.getDocumentSummary());
        api.setStorageKey(domain.getStorageKey());
        api.setCreatedAt(domain.getCreatedAt());
        return api;
    }
    
    public static List<DocumentApproval> toApiList(List<com.tosspaper.models.domain.DocumentApproval> domainList) {
        if (domainList == null) {
            return List.of();
        }
        return domainList.stream()
            .map(DocumentApprovalMapper::toApi)
            .collect(Collectors.toList());
    }
    
    public static DocumentApprovalList toApiListWithPagination(DocumentApprovalApiService.DocumentApprovalListResponse serviceResponse) {
        DocumentApprovalList apiList = new DocumentApprovalList();
        apiList.setData(toApiList(serviceResponse.data()));

        Pagination apiPagination = new Pagination();
        apiPagination.setCursor(serviceResponse.nextCursor());
        apiList.setPagination(apiPagination);

        return apiList;
    }

    public static com.tosspaper.generated.model.DocumentApprovalDetail toDetailApi(
            com.tosspaper.models.domain.DocumentApprovalDetail domain) {
        if (domain == null) {
            return null;
        }

        com.tosspaper.generated.model.DocumentApprovalDetail api =
            new com.tosspaper.generated.model.DocumentApprovalDetail();

        // Only include fields actually used in the UI
        api.setApprovalId(domain.getApprovalId());
        api.setAssignedId(domain.getAssignedId());
        api.setCompanyId(domain.getCompanyId());
        api.setDocumentType(domain.getDocumentType());
        api.setExternalDocumentNumber(domain.getExternalDocumentNumber());
        api.setPoNumber(domain.getPoNumber());
        api.setFromEmail(domain.getFromEmail());
        api.setCreatedAt(domain.getCreatedAt());
        api.setProjectId(domain.getProjectId());
        api.setApprovedAt(domain.getApprovedAt());
        api.setRejectedAt(domain.getRejectedAt());
        api.setReviewedBy(domain.getReviewedBy());
        api.setReviewNotes(domain.getReviewNotes());
        api.setDocumentSummary(domain.getDocumentSummary());
        api.setStorageKey(domain.getStorageKey());

        return api;
    }

    public static com.tosspaper.generated.model.ExtractionResultResponse toExtractionResultApi(
            com.tosspaper.models.domain.ExtractionResult domain) {
        if (domain == null) {
            return null;
        }

        com.tosspaper.generated.model.ExtractionResultResponse api =
            new com.tosspaper.generated.model.ExtractionResultResponse();

        api.setAssignedId(domain.getAssignedId());
        api.setFromEmail(domain.getFromEmail());
        api.setToEmail(domain.getToEmail());
        api.setDocumentType(domain.getDocumentType());
        api.setExtractionResult(domain.getExtractionResult());
        api.setStorageUrl(domain.getStorageUrl());
        api.setPoNumber(domain.getPoNumber());
        api.setProjectId(domain.getProjectId());
        api.poId(domain.getPoId());
        api.setMatchType(domain.getMatchType() != null
            ? MatchType.fromValue(domain.getMatchType())
            : MatchType.PENDING);

        // Parse match report JSON, set null on parse failure
        if (domain.getMatchReport() != null) {
            try {
                api.setMatchReport(MAPPER.readValue(domain.getMatchReport(), new TypeReference<>() {}));
            } catch (JsonProcessingException e) {
                log.error("Failed to parse matchReport JSON for assignedId={}: {}",
                        domain.getAssignedId(), e.getMessage());
                api.setMatchReport(null);
            }
        }

        return api;
    }
}
