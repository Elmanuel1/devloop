package com.tosspaper.delivery_notes;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.DeliveryNotesApi;
import com.tosspaper.generated.model.DeliveryNoteList;
import com.tosspaper.generated.model.DeliveryNote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class DeliveryNoteController implements DeliveryNotesApi {

    private final DeliveryNoteService deliveryNoteService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<DeliveryNoteList> getDeliveryNotes(
            String xContextId,
            String projectId,
            String purchaseOrderId,
            String poNumber,
            Integer limit,
            String cursor,
            String search) {
        log.debug("GET /v1/delivery-notes - projectId={}, purchaseOrderId={}, poNumber={}, limit={}, cursor={}, search={}",
                projectId, purchaseOrderId, poNumber, limit, cursor, search);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        DeliveryNoteList deliveryNotes = deliveryNoteService.getDeliveryNotes(companyId, projectId, purchaseOrderId, poNumber, search, limit, cursor);
        
        return ResponseEntity.ok(deliveryNotes);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<DeliveryNote> getDeliveryNoteById(
            String xContextId,
            String id) {
        log.debug("GET /v1/delivery-notes/{}", id);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        DeliveryNote deliveryNote = deliveryNoteService.getDeliveryNoteById(companyId, id);
        
        return ResponseEntity.ok(deliveryNote);
    }
}

