package com.tosspaper.invoices;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.InvoicesRecord;
import com.tosspaper.generated.model.Invoice;
import com.tosspaper.generated.model.InvoiceDetails;
import com.tosspaper.generated.model.InvoiceStatus;
import com.tosspaper.models.domain.LineItem;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceMapper {
    private final ObjectMapper objectMapper;

    public Invoice toDto(InvoicesRecord record) {
        Invoice invoice = new Invoice();
        invoice.setId(record.getId());
        invoice.setExtractionTaskId(record.getExtractionTaskId());
        invoice.setCompanyId(record.getCompanyId());
        invoice.setDocumentNumber(record.getDocumentNumber());
        invoice.setDocumentDate(record.getDocumentDate());
        invoice.setProjectId(record.getProjectId());
        invoice.setProjectName(record.getProjectName());
        
        // Parse JSONB invoiceDetails
        if (record.getInvoiceDetails() != null) {
            try {
                invoice.setInvoiceDetails(parseInvoiceDetailsOpenApi(record.getInvoiceDetails()));
            } catch (Exception e) {
                log.warn("Failed to parse invoiceDetails for invoice {}", record.getId(), e);
            }
        }
        
        invoice.setPoNumber(record.getPoNumber());
        invoice.setOrderTicketNumber(record.getOrderTicketNumber());

        // Parse JSONB party fields to Map<String, Object> for API response
        if (record.getSellerInfo() != null) {
            invoice.setSellerInfo(partyToMap(parseParty(record.getSellerInfo())));
        }
        if (record.getBuyerInfo() != null) {
            invoice.setBuyerInfo(partyToMap(parseParty(record.getBuyerInfo())));
        }
        if (record.getShipToInfo() != null) {
            invoice.setShipToInfo(partyToMap(parseParty(record.getShipToInfo())));
        }
        if (record.getBillToInfo() != null) {
            invoice.setBillToInfo(partyToMap(parseParty(record.getBillToInfo())));
        }

        // Parse JSONB line items to OpenAPI LineItem objects
        if (record.getLineItems() != null) {
            try {
                List<LineItem> extractionLineItems =
                    parseLineItems(record.getLineItems());
                invoice.setLineItems(convertLineItems(extractionLineItems));
            } catch (Exception e) {
                log.warn("Failed to parse lineItems for invoice {}", record.getId(), e);
            }
        }

        invoice.setReceivedAt(record.getReceivedAt() != null ? record.getReceivedAt() : null);
        invoice.setCreatedAt(record.getCreatedAt() != null ? record.getCreatedAt() : null);
        invoice.setCreatedBy(record.getCreatedBy());
        invoice.setStatus(record.getStatus() != null ? InvoiceStatus.fromValue(record.getStatus()) : null);
        invoice.setPurchaseOrderId(record.getPurchaseOrderId());

        return invoice;
    }

    public List<Invoice> toDtoList(List<InvoicesRecord> records) {
        return records.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    /**
     * Parse JSONB to InvoiceDetails OpenAPI object
     */
    private InvoiceDetails parseInvoiceDetailsOpenApi(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), InvoiceDetails.class);
        } catch (Exception e) {
            log.error("Failed to parse InvoiceDetails JSONB", e);
            return null;
        }
    }

    /**
     * Parse JSONB to InvoiceDetails extraction DTO object
     */
    private com.tosspaper.models.extraction.dto.InvoiceDetails parseInvoiceDetails(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), com.tosspaper.models.extraction.dto.InvoiceDetails.class);
        } catch (Exception e) {
            log.error("Failed to parse InvoiceDetails JSONB", e);
            return null;
        }
    }

    /**
     * Parse JSONB to Party extraction DTO object
     */
    private com.tosspaper.models.extraction.dto.Party parseParty(JSONB jsonb) {
        if (jsonb == null) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonb.data(), com.tosspaper.models.extraction.dto.Party.class);
        } catch (Exception e) {
            log.error("Failed to parse Party JSONB", e);
            return null;
        }
    }

    /**
     * Convert Party extraction DTO object to Map for API response
     */
    private Map<String, Object> partyToMap(com.tosspaper.models.extraction.dto.Party party) {
        if (party == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(party, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to convert Party to Map", e);
            return null;
        }
    }

    /**
     * Parse JSONB to List of extraction DTO LineItem objects
     */
    private List<com.tosspaper.models.domain.LineItem> parseLineItems(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse LineItem array JSONB", e);
            return null;
        }
    }

    /**
     * Convert extraction DTO LineItem to OpenAPI LineItem
     */
    private List<com.tosspaper.generated.model.LineItem> convertLineItems(
            List<LineItem> extractionLineItems) {
        if (extractionLineItems == null) {
            return null;
        }
        return extractionLineItems.stream()
            .map(this::convertLineItem)
            .collect(Collectors.toList());
    }

    /**
     * Convert single extraction DTO LineItem to OpenAPI LineItem
     */
    private com.tosspaper.generated.model.LineItem convertLineItem(com.tosspaper.models.domain.LineItem extractionItem) {
        if (extractionItem == null) {
            return null;
        }

        com.tosspaper.generated.model.LineItem apiItem = new com.tosspaper.generated.model.LineItem();
        apiItem.setItemNo(extractionItem.getLineNumber()); // Use lineNumber for itemNo
        apiItem.setItemCode(extractionItem.getItemCode()); // Correctly map itemCode
        apiItem.setDescription(extractionItem.getDescription());
        apiItem.setUnit(extractionItem.getUnitOfMeasure());
        apiItem.setQuantity(extractionItem.getQuantity());
        apiItem.setUnitCost(extractionItem.getUnitPrice());
        apiItem.setTotal(extractionItem.getTotal());
        apiItem.setTicketNumber(extractionItem.getTicketNumber());

        // Convert ship_date string to LocalDate if present
        if (extractionItem.getShipDate() != null) {
            try {
                apiItem.setShipDate(LocalDate.parse(extractionItem.getShipDate()));
            } catch (Exception e) {
                log.warn("Failed to parse ship date: {}", extractionItem.getShipDate(), e);
            }
        }

        return apiItem;
    }

    @SneakyThrows
    public String toInvoiceDetail(com.tosspaper.models.extraction.dto.InvoiceDetails invoiceDetails) {
        return objectMapper.writeValueAsString(invoiceDetails);
    }

    /**
     * Convert InvoicesRecord to Invoice domain model.
     */
    public com.tosspaper.models.domain.Invoice toDomain(InvoicesRecord record) {
        if (record == null) {
            return null;
        }
        return com.tosspaper.models.domain.Invoice.builder()
                .documentNumber(record.getDocumentNumber())
                .documentDate(record.getDocumentDate())
                .poNumber(record.getPoNumber())
                .sellerInfo(parseParty(record.getSellerInfo()))
                .buyerInfo(parseParty(record.getBuyerInfo()))
                .shipToInfo(parseParty(record.getShipToInfo()))
                .billToInfo(parseParty(record.getBillToInfo()))
                .lineItems(parseLineItems(record.getLineItems()))
                .invoiceDetails(parseInvoiceDetails(record.getInvoiceDetails()))
                .companyId(record.getCompanyId())
                .projectId(record.getProjectId())
                .assignedId(record.getExtractionTaskId())
                .status(record.getStatus() != null 
                        ? findStatusByValue(record.getStatus()) 
                        : null)
                .lastSyncAt(record.getLastSyncAt())
                .build();
    }

    /**
     * Convert domain Invoice to generated OpenAPI Invoice.
     * Maps from domain model (extraction DTOs) to generated API model.
     */
    public Invoice toDto(com.tosspaper.models.domain.Invoice domain) {
        if (domain == null) {
            return null;
        }
        
        Invoice dto = new Invoice();
        dto.setDocumentNumber(domain.getDocumentNumber());
        dto.setDocumentDate(domain.getDocumentDate());
        dto.setPoNumber(domain.getPoNumber());
        dto.setOrderTicketNumber(domain.getJobNumber()); // jobNumber maps to orderTicketNumber in API
        dto.setProjectName(null); // Not in domain model
        
        // Convert Party extraction DTOs to Maps for API
        if (domain.getSellerInfo() != null) {
            dto.setSellerInfo(partyToMap(domain.getSellerInfo()));
        }
        if (domain.getBuyerInfo() != null) {
            dto.setBuyerInfo(partyToMap(domain.getBuyerInfo()));
        }
        if (domain.getShipToInfo() != null) {
            dto.setShipToInfo(partyToMap(domain.getShipToInfo()));
        }
        if (domain.getBillToInfo() != null) {
            dto.setBillToInfo(partyToMap(domain.getBillToInfo()));
        }
        
        // Convert LineItems from domain to generated
        if (domain.getLineItems() != null) {
            dto.setLineItems(convertLineItems(domain.getLineItems()));
        }
        
        // Convert InvoiceDetails from extraction DTO to generated
        if (domain.getInvoiceDetails() != null) {
            dto.setInvoiceDetails(convertInvoiceDetails(domain.getInvoiceDetails()));
        }
        
        // Map status from domain enum to generated enum
        if (domain.getStatus() != null) {
            dto.setStatus(convertStatus(domain.getStatus()));
        }
        
        dto.setCompanyId(domain.getCompanyId());
        dto.setProjectId(domain.getProjectId());
        dto.setExtractionTaskId(domain.getAssignedId());
        
        return dto;
    }

    /**
     * Convert InvoiceDetails from extraction DTO to generated OpenAPI model.
     */
    private InvoiceDetails convertInvoiceDetails(com.tosspaper.models.extraction.dto.InvoiceDetails domain) {
        if (domain == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(domain, InvoiceDetails.class);
        } catch (Exception e) {
            log.error("Failed to convert InvoiceDetails to generated model", e);
            return null;
        }
    }

    /**
     * Convert domain Status enum to generated InvoiceStatus enum.
     */
    private InvoiceStatus convertStatus(com.tosspaper.models.domain.Invoice.Status domainStatus) {
        if (domainStatus == null) {
            return null;
        }
        // Map domain status values to generated status values
        // Generated has: draft, pending
        // Domain has: PENDING("pending"), ACCEPTED("accepted")
        return switch (domainStatus) {
            case PENDING -> InvoiceStatus.PENDING;
            case ACCEPTED -> InvoiceStatus.PENDING; // ACCEPTED maps to PENDING in generated (accepted invoices are "pending" in API)
        };
    }

    private com.tosspaper.models.domain.Invoice.Status findStatusByValue(String statusValue) {
        for (com.tosspaper.models.domain.Invoice.Status status : com.tosspaper.models.domain.Invoice.Status.values()) {
            if (status.getValue().equals(statusValue)) {
                return status;
            }
        }
        return null;
    }
}

