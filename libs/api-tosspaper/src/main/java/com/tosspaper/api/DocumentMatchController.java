package com.tosspaper.api;

import com.tosspaper.aiengine.service.DocumentMatchService;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.DocumentMatchingApi;
import com.tosspaper.generated.model.LinkPoRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for document match operations.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentMatchController implements DocumentMatchingApi {

    private final DocumentMatchService documentMatchService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'extraction:approve')")
    public ResponseEntity<Void> linkPurchaseOrder(String xContextId, String assignedId, LinkPoRequest linkPoRequest) {
        log.info("POST /api/v1/document-matches/{}/link-po - poId={}", assignedId, linkPoRequest.getPoNumber());

        try {
            documentMatchService.initiateManualLink(HeaderUtils.parseCompanyId(xContextId), assignedId, linkPoRequest.getPoNumber());
            log.info("Manual PO link initiated for assignedId: {}, poId: {}", assignedId, linkPoRequest.getPoNumber());
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Failed to initiate manual PO link for assignedId: {}", assignedId, e);
            throw new RuntimeException("Failed to initiate PO link", e);
        }
    }

    @Override
    // TODO: Security issue - missing xContextId parameter for permission check
    public ResponseEntity<Void> rematch(String assignedId) {
        log.info("POST /api/v1/document-matches/{}/rematch", assignedId);

        try {
            documentMatchService.initiateAutoMatch(assignedId);
            log.info("Re-match initiated for assignedId: {}", assignedId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Failed to initiate re-match for assignedId: {}", assignedId, e);
            throw new RuntimeException("Failed to initiate re-match", e);
        }
    }

}

