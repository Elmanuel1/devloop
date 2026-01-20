package com.tosspaper.models.mapper;

import com.tosspaper.models.domain.*;
import com.tosspaper.models.extraction.dto.Charge;
import com.tosspaper.models.extraction.dto.DeliveryTransaction;
import com.tosspaper.models.extraction.dto.Extraction;
import com.tosspaper.models.extraction.dto.Party;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maps from Extraction POJO (generated from extraction.json schema) to domain models.
 * This is the bridge between what Reducto returns and what we store in the database.
 */
@Slf4j
@Component
public class ExtractionToDomainMapper {

    /**
     * Convert Extraction to Invoice domain model.
     */
    public Invoice toInvoice(Extraction extraction, DocumentApproval documentApproval) {
        return Invoice.builder()
            .documentNumber(extraction.getDocumentNumber())
            .documentDate(toLocalDate(extraction.getDocumentDate()))
            .poNumber(extraction.getCustomerPONumber())
            .jobNumber(extraction.getJobOrderNumber())
            .sellerInfo(extractPartyByRole(extraction.getParties(), Party.Role.SELLER))
            .buyerInfo(extractPartyByRole(extraction.getParties(), Party.Role.BUYER))
            .shipToInfo(extractPartyByRole(extraction.getParties(), Party.Role.SHIP_TO))
            .billToInfo(extractPartyByRole(extraction.getParties(), Party.Role.BILL_TO))
            .lineItems(transformToLineItems(extraction.getDeliveryTransactions()))
            .invoiceDetails(extraction.getInvoiceDetails())
            .companyId(documentApproval.getCompanyId())
            .projectId(documentApproval.getProjectId())
            .assignedId(documentApproval.getAssignedId())
            .build();
    }

    /**
     * Convert Extraction to DeliverySlip domain model.
     */
    public DeliverySlip toDeliverySlip(Extraction extraction, DocumentApproval documentApproval) {
        return DeliverySlip.builder()
            .documentNumber(extraction.getDocumentNumber())
            .documentDate(toLocalDate(extraction.getDocumentDate()))
            .poNumber(extraction.getCustomerPONumber())
            .jobNumber(extraction.getJobOrderNumber())
            .sellerInfo(extractPartyByRole(extraction.getParties(), Party.Role.SELLER))
            .buyerInfo(extractPartyByRole(extraction.getParties(), Party.Role.BUYER))
            .shipToInfo(extractPartyByRole(extraction.getParties(), Party.Role.SHIP_TO))
            .billToInfo(extractPartyByRole(extraction.getParties(), Party.Role.BILL_TO))
            .lineItems(transformToLineItems(extraction.getDeliveryTransactions()))
            .shipmentDetails(extraction.getShipmentDetails())
            .deliveryAcknowledgement(extraction.getDeliveryAcknowledgement())
            .companyId(documentApproval.getCompanyId())
            .projectId(documentApproval.getProjectId())
            .assignedId(documentApproval.getAssignedId())
            .build();
    }

    /**
     * Convert Extraction to DeliveryNote domain model.
     */
    public DeliveryNote toDeliveryNote(Extraction extraction,  DocumentApproval documentApproval) {
        return DeliveryNote.builder()
            .documentNumber(extraction.getDocumentNumber())
            .documentDate(toLocalDate(extraction.getDocumentDate()))
            .poNumber(extraction.getCustomerPONumber())
            .jobNumber(extraction.getJobOrderNumber())
            //.projectName(extraction.getProjectName())
            .sellerInfo(extractPartyByRole(extraction.getParties(), Party.Role.SELLER))
            .buyerInfo(extractPartyByRole(extraction.getParties(), Party.Role.BUYER))
            .shipToInfo(extractPartyByRole(extraction.getParties(), Party.Role.SHIP_TO))
            .billToInfo(extractPartyByRole(extraction.getParties(), Party.Role.BILL_TO))
            .lineItems(transformToLineItems(extraction.getDeliveryTransactions()))
            .shipmentDetails(extraction.getShipmentDetails())
            .deliveryAcknowledgement(extraction.getDeliveryAcknowledgement())
            .companyId(documentApproval.getCompanyId())
            .projectId(documentApproval.getProjectId())
            .assignedId(documentApproval.getAssignedId())
            .build();
    }

    /**
     * Convert Extraction to appropriate domain model based on document type.
     */
    public Object toDomainModel(Extraction extraction, DocumentApproval approvalRecord) {
        return switch (extraction.getDocumentType()) {
            case INVOICE -> toInvoice(extraction, approvalRecord);
            case DELIVERY_SLIP -> toDeliverySlip(extraction, approvalRecord);
            case DELIVERY_NOTE -> toDeliveryNote(extraction, approvalRecord);
            default -> throw new IllegalArgumentException("Unsupported document type: " + approvalRecord.getDocumentType());
        };
    }

    /**
     * Extract party by role from parties list.
     */
    private Party extractPartyByRole(List<Party> parties, Party.Role role) {
        if (parties == null || parties.isEmpty()) {
            return null;
        }

        return parties.stream()
            .filter(p -> role.equals(p.getRole()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Transform deliveryTransactions to line items for database storage.
     * Flattens charges from all transactions and converts to domain LineItem objects.
     */
    private List<LineItem> transformToLineItems(List<DeliveryTransaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return Collections.emptyList();
        }

        List<LineItem> lineItems = new ArrayList<>();

        for (DeliveryTransaction transaction : transactions) {
            String ticketId = transaction.getTicketId();
            LocalDate deliveryDate = toLocalDate(transaction.getDeliveryDate());

            if (transaction.getCharges() != null) {
                for (Charge charge : transaction.getCharges()) {
                    // Calculate total if unitPrice and quantity are available
                    Double total = null;
                    if (charge.getUnitPrice() != null && charge.getQuantity() != null) {
                        total = charge.getUnitPrice() * charge.getQuantity();
                    }

                    // Convert Charge to domain LineItem with all Charge fields + flattened metadata
                    LineItem lineItem = LineItem.builder()
                        .lineNumber(charge.getLineNumber())
                        .itemCode(charge.getItemCode())
                        .description(charge.getDescription())
                        .unitOfMeasure(charge.getUnitOfMeasure())
                        .quantity(charge.getQuantity())
                        .unitPrice(charge.getUnitPrice())
                        .weight(charge.getWeight())
                        .total(total)
                        .ticketNumber(ticketId)
                        .shipDate(deliveryDate != null ? deliveryDate.toString() : null)
                        .build();

                    lineItems.add(lineItem);
                }
            }
        }

        log.info("Transformed {} LineItem objects from Charge objects", lineItems.size());
        return lineItems;
    }

    /**
     * Parse ISO date string (YYYY-MM-DD) to LocalDate.
     * The AI prompt enforces ISO format, so we primarily use ISO_LOCAL_DATE parser.
     * Falls back to common formats if needed for robustness.
     */
    private LocalDate toLocalDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        // Primary: ISO format (YYYY-MM-DD) - enforced by AI prompt
        try {
            return LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            // Fallback: Try other common formats for robustness
            DateTimeFormatter[] fallbackFormatters = {
                DateTimeFormatter.ofPattern("M/d/yyyy"),    // MM/DD/YYYY or M/D/YYYY
                DateTimeFormatter.ofPattern("d/M/yyyy"),    // DD/MM/YYYY or D/M/YYYY
                DateTimeFormatter.ofPattern("yyyy/MM/dd")   // YYYY/MM/DD
            };

            for (DateTimeFormatter formatter : fallbackFormatters) {
                try {
                    return LocalDate.parse(dateString, formatter);
                } catch (DateTimeParseException ignored) {
                    // Try next formatter
                }
            }

            log.warn("Failed to parse date string '{}' - expected ISO format YYYY-MM-DD", dateString);
            return null;
        }
    }
}

