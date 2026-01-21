package com.tosspaper.document_approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.model.message.Message;
import com.mailgun.model.message.MessageResponse;
import com.tosspaper.models.service.DocumentApprovalService;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.config.MailgunProperties;
import com.tosspaper.models.domain.ApprovalContext;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.extraction.dto.Extraction;
import feign.FeignException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service implementation for processing document approval events.
 * Handles business logic: fetching data, parsing JSON to POJOs, and delegating to repository.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentApprovalEmailProcessingServiceImpl implements DocumentApprovalEmailProcessingService {

    private final MailgunProperties mailgunProperties;
    private final MailgunMessagesApi mailgunMessagesApi;
    private final ObjectMapper objectMapper;
    private final com.tosspaper.models.mapper.ExtractionToDomainMapper extractionToDomainMapper;
    private final ExtractionTaskRepository extractionTaskRepository;
    private final DocumentApprovalService documentApprovalService;

    private final ObservationRegistry observationRegistry;

    @SneakyThrows
    @Override
    @Observed(
        name = "document.approval.process",
        contextualName = "Process Document Approval Email",
        lowCardinalityKeyValues = {"service", "approval", "operation", "send-email"}
    )
    public void processDocumentApproval(String assignedId) {
        // Add dynamic span attributes only if sampled (to reduce cost)
        Observation observation = observationRegistry.getCurrentObservation();
        if (observation != null && assignedId != null) {
            observation.highCardinalityKeyValue("assignedId", assignedId);
        }

        var extractionTask = extractionTaskRepository.findByAssignedId(assignedId);
        log.info("Processing email for approved extraction task for {}", assignedId);

        var documentApproval = documentApprovalService.findByAssignedId(assignedId).orElseThrow();

        if (documentApproval.isPending()) {
            log.info("Document approval is pending for {} and cannot send a mail for it yet", assignedId);
            return;  // Don't send email for pending approvals
        }
        Extraction extraction = objectMapper.readValue(extractionTask.getConformedJson(), Extraction.class);
        ApprovalContext<Extraction> approvalContext = ApprovalContext.<Extraction>builder()
                .extraction(extraction)
                .documentApproval(documentApproval)
                .build();

        // If rejected, just send rejection email
        if (documentApproval.isRejected()) {
            // Build minimal context for rejection email using approval record data


            sendRejectedEmail(approvalContext);
            log.info("Completed rejection processing for extraction task: {}", documentApproval.getAssignedId());
            return;
        }

        // Send approval email with actual document data
        sendDocumentAcceptedEmail(approvalContext);

        log.info("Completed document approval processing for extraction task: {}", documentApproval.getAssignedId());
    }

    private void sendDocumentAcceptedEmail(ApprovalContext<Extraction> context) {
        var documentType = DocumentType.fromString(context.getDocumentApproval().getDocumentType());
        String documentTypeLabel = getDocumentTypeLabel(documentType);
        
        try {
            // Extract common fields from domain model based on document type
            String documentNumber = context.getExtraction().getDocumentNumber();
            String documentDate = "N/A";
            List<com.tosspaper.models.domain.LineItem> lineItems = new ArrayList<>();
            String contactName = "Vendor";
            
            // Cast to appropriate domain type and extract fields
            switch (documentType) {
                case INVOICE -> {
                    com.tosspaper.models.domain.Invoice invoice = extractionToDomainMapper.toInvoice(context.getExtraction(), context.getDocumentApproval());
                    documentDate = invoice.getDocumentDate() != null ? invoice.getDocumentDate().toString() : "N/A";
                    lineItems = invoice.getLineItems();
                    if (invoice.getSellerInfo() != null && invoice.getSellerInfo().getName() != null) {
                        contactName = invoice.getSellerInfo().getName();
                    }
                }
                case DELIVERY_SLIP -> {
                    com.tosspaper.models.domain.DeliverySlip slip = extractionToDomainMapper.toDeliverySlip(context.getExtraction(), context.getDocumentApproval());
                    documentDate = slip.getDocumentDate() != null ? slip.getDocumentDate().toString() : "N/A";
                    lineItems = slip.getLineItems();
                    if (slip.getSellerInfo() != null && slip.getSellerInfo().getName() != null) {
                        contactName = slip.getSellerInfo().getName();
                    }
                }
                case DELIVERY_NOTE -> {
                    com.tosspaper.models.domain.DeliveryNote note = extractionToDomainMapper.toDeliveryNote(context.getExtraction(), context.getDocumentApproval());
                    documentDate = note.getDocumentDate() != null ? note.getDocumentDate().toString() : "N/A";
                    lineItems = note.getLineItems();
                    if (note.getSellerInfo() != null && note.getSellerInfo().getName() != null) {
                        contactName = note.getSellerInfo().getName();
                    }
                }
                case UNKNOWN, PURCHASE_ORDER -> {
                    log.warn("Cannot send acceptance email for {} document type: {}", documentType, context.getDocumentApproval().getAssignedId());
                    // Use basic extraction data for unknown/purchase order types
                    documentDate = context.getExtraction().getDocumentDate() != null ? context.getExtraction().getDocumentDate() : "N/A";
                    lineItems = new ArrayList<>();
                }
            }
            
            String subject = String.format("%s Received and Validated - %s", documentTypeLabel, 
                documentNumber != null ? documentNumber : "N/A");

            // Calculate total from line items
            double total = lineItems != null ? lineItems.stream()
                    .mapToDouble(item -> {
                        if (item.getUnitPrice() != null && item.getQuantity() != null) {
                            return item.getUnitPrice() * item.getQuantity();
                        }
                        return 0.0;
                    }).sum() : 0.0;

            String totalFormatted = NumberFormat.getCurrencyInstance(Locale.CANADA).format(total);

            // Build line items summary
            StringBuilder lineItemsSummary = new StringBuilder();
            if (lineItems != null) {
                lineItems.forEach(item -> lineItemsSummary.append(String.format("  - %s (Qty: %s %s) - %s\n",
                        item.getDescription() != null ? item.getDescription() : "N/A",
                        item.getQuantity() != null ? item.getQuantity() : "0",
                        item.getUnitOfMeasure() != null ? item.getUnitOfMeasure() : "",
                        item.getUnitPrice() != null && item.getQuantity() != null
                                ? NumberFormat.getCurrencyInstance(Locale.CANADA).format(item.getUnitPrice() * item.getQuantity())
                                : "$0.00")));
            }

            String body = String.format("""
                Dear %s,

                Your %s has been received and validated:

                Document Number: %s
                Document Date: %s
                PO Number: %s
                Total Amount: %s

                Line Items:
                %s

                This %s has been successfully validated and entered into our system for processing. This does not constitute payment approval.

                Please do not reply to this email. This is an automated notification.

                Best regards,
                TossPaper Team
                """,
                    contactName,
                    documentTypeLabel.toLowerCase(),
                    documentNumber != null ? documentNumber : "N/A",
                    documentDate,
                    context.getDocumentApproval().getPoNumber() != null ? context.getDocumentApproval().getPoNumber() : "N/A",
                    totalFormatted,
                    lineItemsSummary.toString().trim(),
                    documentTypeLabel.toLowerCase());

            // Send to primary recipient + regular recipients for this category
            List<String> recipients = getRecipients(context.getDocumentApproval().getFromEmail(), documentType);
            sendEmail(recipients, subject, body);
            log.info("Sent {} accepted email for task {} to {} recipients", documentTypeLabel.toLowerCase(),
                    context.getDocumentApproval().getAssignedId(), recipients.size());
        } catch (Exception e) {
            log.error("Failed to send {} accepted email for task {}", documentTypeLabel.toLowerCase(),
                    context.getDocumentApproval().getAssignedId(), e);
        }
    }

    private void sendRejectedEmail(ApprovalContext<?> context) {
        try {
            var documentType = DocumentType.fromString(context.getDocumentApproval().getDocumentType());

            String documentTypeLabel = getDocumentTypeLabel(documentType);
            String subject = String.format("%s Rejected", documentTypeLabel);

            // Build document identification info
            StringBuilder documentInfo = new StringBuilder();
            if (context.getDocumentApproval().getExternalDocumentNumber() != null && !context.getDocumentApproval().getExternalDocumentNumber().isBlank()) {
                documentInfo.append(String.format("Document Number: %s\n", context.getDocumentApproval().getExternalDocumentNumber()));
            }
            if (context.getDocumentApproval().getPoNumber() != null && !context.getDocumentApproval().getPoNumber().isBlank()) {
                documentInfo.append(String.format("PO Number: %s\n", context.getDocumentApproval().getPoNumber()));
            }

            String documentInfoText = !documentInfo.isEmpty()
                    ? documentInfo.toString().trim()
                    : "Document information not available";

            String body = String.format("""
                Dear Vendor,

                Your %s has been rejected:

                %s

                Note: %s

                Please review and resubmit if necessary. If you have questions, please contact us.

                Please do not reply to this email. This is an automated notification.

                Best regards,
                TossPaper Team
                """,
                    documentTypeLabel.toLowerCase(),
                    documentInfoText,
                    context.getDocumentApproval().getReviewNotes() != null ? context.getDocumentApproval().getReviewNotes() : "No note provided");

            // Send to primary recipient only for rejections
            sendEmail(List.of(context.getDocumentApproval().getFromEmail()), subject, body);
            log.info("Sent {} rejected email for extraction task {} to {}", documentTypeLabel.toLowerCase(),
                    context.getDocumentApproval().getAssignedId(), context.getDocumentApproval().getFromEmail());
        } catch (Exception e) {
            log.error("Failed to send rejected email for extraction task {}", context.getDocumentApproval().getAssignedId(), e);
        }
    }

    private String getDocumentTypeLabel(DocumentType documentType) {
        return switch (documentType) {
            case INVOICE -> "Invoice";
            case DELIVERY_SLIP -> "Delivery Slip";
            case DELIVERY_NOTE -> "Delivery Note";
            default -> "Document";
        };
    }

    private List<String> getRecipients(String primaryEmail, DocumentType documentType) {
        List<String> recipients = new ArrayList<>();
        recipients.add(primaryEmail);

        return recipients;
    }

    /**
     * Create document (Invoice/DeliverySlip/DeliveryNote) from extraction data.
     * This is called when a document is approved with user-modified data.
     * Returns the domain model for use in email rendering.
     */
    @SneakyThrows


    private void sendEmail(List<String> recipients, String subject, String body) {
        try {
            Message message = Message.builder()
                    .from(mailgunProperties.getFromEmail())
                    .to(recipients)
                    .subject(subject)
                    .text(body)
                    .build();

            log.debug("Sending email via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                    mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), recipients, subject);

            MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
            log.info("Mailgun email sent successfully - Message ID: {}, Recipients: {}", response.getMessage(), recipients.size());
        } catch (FeignException.Unauthorized e) {
            log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                    mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
            throw new RuntimeException("Mailgun authentication failed - please verify API key", e);
        } catch (Exception e) {
            log.error("Failed to send email via Mailgun - Domain: {}, To: {}, Subject: {}",
                    mailgunProperties.getDomain(), recipients, subject, e);
            throw new RuntimeException("Failed to send email", e);
        }
    }
}

