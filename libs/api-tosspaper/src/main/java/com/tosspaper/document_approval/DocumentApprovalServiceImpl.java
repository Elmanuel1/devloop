package com.tosspaper.document_approval;

import com.tosspaper.aiengine.repository.DocumentApprovalRepository;
import com.tosspaper.delivery_notes.DeliveryNoteRepository;
import com.tosspaper.delivery_slips.DeliverySlipRepository;
import com.tosspaper.invoices.InvoiceRepository;
import com.tosspaper.purchaseorder.PurchaseOrderRepository;
import com.tosspaper.models.domain.*;
import com.tosspaper.models.exception.BadRequestException;
import com.tosspaper.models.exception.ForbiddenException;
import com.tosspaper.models.extraction.dto.Extraction;
import com.tosspaper.models.mapper.ExtractionToDomainMapper;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import com.tosspaper.models.utils.CursorUtils;
import com.tosspaper.models.query.DocumentApprovalQuery;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of DocumentApprovalService.
 * Handles approval/rejection of extraction matches.
 * PO status updates and invoice/delivery_slip creation are handled
 * asynchronously via Redis streams.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentApprovalServiceImpl implements com.tosspaper.document_approval.DocumentApprovalApiService,
        com.tosspaper.models.service.DocumentApprovalService {

    private final DocumentApprovalRepository documentApprovalRepository;
    private final MessagePublisher messagePublisher;
    private final DSLContext dslContext;
    private final ExtractionToDomainMapper mapper;
    private final InvoiceRepository invoiceRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliverySlipRepository deliverySlipRepository;
    private final PurchaseOrderLookupService poLookupService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final com.tosspaper.invoices.InvoiceMapper invoiceMapper;
    private final com.tosspaper.integrations.service.IntegrationConnectionService integrationConnectionService;
    private final com.tosspaper.integrations.push.IntegrationPushStreamPublisher integrationPushStreamPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public DocumentApproval findById(String id) {
        return documentApprovalRepository.findById(id);
    }

    @Override
    public List<DocumentApproval> findByQuery(DocumentApprovalQuery query) {
        return documentApprovalRepository.findByQuery(query);
    }

    @Override
    public Optional<DocumentApproval> findByAssignedId(String assignedId) {
        return documentApprovalRepository.findByAssignedId(assignedId);
    }

    @Override
    public List<DocumentApproval> findApprovedForSync(String connectionId, OffsetDateTime cursorAt, String cursorId,
            int limit) {
        return documentApprovalRepository.findApprovedForSync(connectionId, cursorAt, cursorId, limit);
    }

    @SneakyThrows
    private void publishDocumentApprovedEvent(String assignedId) {
        Map<String, String> eventData = new HashMap<>();
        eventData.put("assignedId", assignedId);

        messagePublisher.publish("document-approved-events", eventData);
        log.info("Published document-approved event for approval: {}", assignedId);
    }

    @Override
    public com.tosspaper.document_approval.DocumentApprovalApiService.DocumentApprovalListResponse listDocumentApprovalsFromApi(
            Long companyId,
            Integer pageSize,
            String cursor,
            String status,
            String documentType,
            String fromEmail,
            OffsetDateTime createdDateFrom,
            OffsetDateTime createdDateTo,
            String projectId) {

        log.debug(
                "Listing document approvals from API - companyId={}, pageSize={}, cursor={}, status={}, documentType={}, fromEmail={}, createdFrom={}, createdTo={}, projectId={}",
                companyId, pageSize, cursor, status, documentType, fromEmail, createdDateFrom, createdDateTo,
                projectId);

        // Decode cursor if provided
        OffsetDateTime cursorCreatedAt = null;
        String cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtils.CursorPair cursorPair = CursorUtils.decodeCursor(cursor);
                cursorCreatedAt = cursorPair.createdAt();
                cursorId = cursorPair.id();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format", e);
            }
        }

        // Set default pageSize if not provided or invalid
        int validatedPageSize = pageSize != null && pageSize > 0 ? pageSize : 20;

        // Build query
        var query = DocumentApprovalQuery.builder()
                .companyId(String.valueOf(companyId))
                .pageSize(validatedPageSize)
                .cursorCreatedAt(cursorCreatedAt)
                .cursorId(cursorId)
                .status(status)
                .documentType(documentType)
                .fromEmail(fromEmail)
                .createdDateFrom(createdDateFrom)
                .createdDateTo(createdDateTo)
                .projectId(projectId)
                .build();

        // Fetch approvals
        List<DocumentApproval> approvals = documentApprovalRepository.findByQuery(query);

        // Generate cursor from last record if we got exactly pageSize records
        // (indicates there might be more)
        String nextCursor = null;
        if (approvals.size() == validatedPageSize) {
            DocumentApproval lastApproval = approvals.getLast();
            // Use the appropriate timestamp based on status for cursor
            OffsetDateTime cursorTimestamp = lastApproval.getApprovedAt() != null
                    ? lastApproval.getApprovedAt()
                    : (lastApproval.getRejectedAt() != null
                            ? lastApproval.getRejectedAt()
                            : lastApproval.getCreatedAt());
            nextCursor = CursorUtils.encodeCursor(cursorTimestamp, lastApproval.getId());
        }

        return new com.tosspaper.document_approval.DocumentApprovalApiService.DocumentApprovalListResponse(approvals,
                nextCursor);
    }

    @Override
    public void reviewExtraction(Long companyId, String approvalId, boolean approved, String reviewedBy,
            String reviewNotes, com.tosspaper.models.extraction.dto.Extraction extraction) {
        ReviewStatus status = approved
                ? ReviewStatus.APPROVED
                : ReviewStatus.REJECTED;
        log.info("Reviewing document approval: companyId={}, approvalId={}, status={}, reviewedBy={}, hasExtraction={}",
                companyId, approvalId, status, reviewedBy, extraction != null);

        // 1. Fetch document approval and validate company ownership
        var documentApproval = documentApprovalRepository.findById(approvalId);

        if (!documentApproval.getCompanyId().equals(companyId)) {
            throw new ForbiddenException("Document approval company does not belong to this company");
        }

        if (!documentApproval.isPending()) {
            throw new BadRequestException("api.document_approved", "Document has already been approved");
        }

        assert extraction != null;

        if (status == ReviewStatus.APPROVED) {
            var poInformation = poLookupService.findByCompanyIdAndDisplayId(companyId,
                    extraction.getCustomerPONumber());
            if (poInformation.isEmpty()) {
                throw new BadRequestException("api.po.notfound", "PO provided is not found on our system");
            }
            dslContext.transaction(tx -> {
                var approvalRecord = documentApprovalRepository.approve(tx.dsl(), documentApproval.getId(),
                        poInformation.get().projectId(), reviewedBy, reviewNotes);
                createDocument(tx.dsl(), approvalRecord, extraction);
                // Update PO status to IN_PROGRESS if currently PENDING
                purchaseOrderRepository.updateStatusToInProgressIfPending(tx.dsl(), poInformation.get().id(),
                        companyId);
            });

        } else {
            documentApprovalRepository.reject(documentApproval.getId(), reviewedBy, reviewNotes);
        }

        publishDocumentApprovedEvent(documentApproval.getAssignedId());

        log.info(
                "Completed review for approval {}: {}. PO updates and document creation will be handled asynchronously.",
                approvalId, status);
    }

    private void createDocument(DSLContext context, com.tosspaper.models.domain.DocumentApproval approval,
            Extraction extractionData) {
        log.info("Creating document from user data for approval: {}, type: {}", approval.getId(),
                extractionData.getDocumentType());

        // Convert Extraction DTO to domain model
        Object domainModel = mapper.toDomainModel(extractionData, approval);

        switch (extractionData.getDocumentType()) {
            case INVOICE -> {
                var record = invoiceRepository.create(context, (com.tosspaper.models.domain.Invoice) domainModel);
                // Convert record to domain for event
                var createdInvoice = invoiceMapper.toDomain(record);
                // Publish event
                publishIntegrationPushEventIfNeeded(createdInvoice, approval.getReviewedBy());
            }

            case DELIVERY_SLIP -> deliverySlipRepository.create(context, (DeliverySlip) domainModel);

            case DELIVERY_NOTE -> deliveryNoteRepository.create(context, (DeliveryNote) domainModel);

            case UNKNOWN -> {
                log.warn("Cannot create document for {} document type: {}", extractionData.getDocumentType(),
                        approval.getId());
                throw new IllegalArgumentException(
                        "Cannot create document from " + extractionData.getDocumentType() + " document type");
            }
        }
    }

    /**
     * Publish integration push event for invoice if there's an active ACCOUNTING
     * connection.
     */
    private void publishIntegrationPushEventIfNeeded(com.tosspaper.models.domain.Invoice invoice, String updatedBy) {
        try {
            Long companyId = invoice.getCompanyId();
            log.debug("Checking for active ACCOUNTING connection for company {} to publish invoice push event",
                    companyId);

            // Get active ACCOUNTING connection for the company
            var connectionOpt = integrationConnectionService.findActiveByCompanyAndCategory(companyId,
                    com.tosspaper.models.domain.integration.IntegrationCategory.ACCOUNTING);

            if (connectionOpt.isEmpty()) {
                log.debug("No active ACCOUNTING connection for company {}, skipping integration push", companyId);
                return;
            }

            com.tosspaper.models.domain.integration.IntegrationConnection connection = connectionOpt.get();
            log.info("Found active ACCOUNTING connection for company {}, publishing invoice push event", companyId);

            // Serialize Invoice to JSON payload
            String payload = objectMapper.writeValueAsString(invoice);

            com.tosspaper.integrations.push.IntegrationPushEvent event = new com.tosspaper.integrations.push.IntegrationPushEvent(
                    connection.getProvider(),
                    com.tosspaper.integrations.provider.IntegrationEntityType.BILL,
                    companyId,
                    connection.getId(),
                    payload,
                    updatedBy);

            integrationPushStreamPublisher.publish(event);
            log.info("Published invoice (bill) push event: id={}, docNumber={}, provider={}",
                    invoice.getId(), invoice.getDocumentNumber(), connection.getProvider());

        } catch (Exception e) {
            log.error("Failed to publish integration push event for invoice: id={}", invoice.getId(), e);
            // Don't throw - we don't want to fail approval if push fails
        }
    }
}
