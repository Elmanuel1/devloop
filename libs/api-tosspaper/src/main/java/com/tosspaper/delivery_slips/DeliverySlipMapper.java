package com.tosspaper.delivery_slips;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord;
import com.tosspaper.generated.model.DeliveryAcknowledgement;
import com.tosspaper.generated.model.DeliverySlip;
import com.tosspaper.generated.model.DeliverySlipStatus;
import com.tosspaper.generated.model.ShipmentDetails;
import com.tosspaper.models.domain.LineItem;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliverySlipMapper {
    private final ObjectMapper objectMapper;

    public DeliverySlip toDto(DeliverySlipsRecord record) {
        DeliverySlip deliverySlip = new DeliverySlip();
        deliverySlip.setId(record.getId());
        deliverySlip.setExtractionTaskId(record.getExtractionTaskId());
        deliverySlip.setCompanyId(record.getCompanyId());
        deliverySlip.setDocumentNumber(record.getDocumentNumber());
        deliverySlip.setDocumentDate(record.getDocumentDate());
        deliverySlip.setProjectId(record.getProjectId());
        deliverySlip.setProjectName(record.getProjectName());
        deliverySlip.setJobNumber(record.getJobNumber());
        deliverySlip.setPoNumber(record.getPoNumber());
        deliverySlip.setDeliveryMethodNote(record.getDeliveryMethodNote());

        // Parse JSONB party fields to Map<String, Object> for API response
        if (record.getSellerInfo() != null) {
            deliverySlip.setSellerInfo(partyToMap(parseParty(record.getSellerInfo())));
        }
        if (record.getBuyerInfo() != null) {
            deliverySlip.setBuyerInfo(partyToMap(parseParty(record.getBuyerInfo())));
        }
        if (record.getShipToInfo() != null) {
            deliverySlip.setShipToInfo(partyToMap(parseParty(record.getShipToInfo())));
        }
        if (record.getBillToInfo() != null) {
            deliverySlip.setBillToInfo(partyToMap(parseParty(record.getBillToInfo())));
        }

        // Parse JSONB line items to OpenAPI LineItem objects
        if (record.getLineItems() != null) {
            try {
                List<LineItem> extractionLineItems =
                    parseLineItems(record.getLineItems());
                deliverySlip.setLineItems(convertLineItems(extractionLineItems));
            } catch (Exception e) {
                log.warn("Failed to parse lineItems for delivery slip {}", record.getId(), e);
            }
        }

        // Parse JSONB shipment details to OpenAPI object
        if (record.getShipmentDetails() != null) {
            try {
                deliverySlip.setShipmentDetails(parseShipmentDetailsOpenApi(record.getShipmentDetails()));
            } catch (Exception e) {
                log.warn("Failed to parse shipmentDetails for delivery slip {}", record.getId(), e);
            }
        }

        // Parse JSONB delivery acknowledgement to OpenAPI object
        if (record.getDeliveryAcknowledgement() != null) {
            try {
                deliverySlip.setDeliveryAcknowledgement(parseDeliveryAckOpenApi(record.getDeliveryAcknowledgement()));
            } catch (Exception e) {
                log.warn("Failed to parse deliveryAcknowledgement for delivery slip {}", record.getId(), e);
            }
        }

        deliverySlip.setCreatedAt(record.getCreatedAt() != null ? record.getCreatedAt() : null);
        deliverySlip.setCreatedBy(record.getCreatedBy());
        deliverySlip.setStatus(record.getStatus() != null ? DeliverySlipStatus.fromValue(record.getStatus()) : null);
        deliverySlip.setPurchaseOrderId(record.getPurchaseOrderId());

        return deliverySlip;
    }

    public List<DeliverySlip> toDtoList(List<DeliverySlipsRecord> records) {
        return records.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    /**
     * Parse JSONB to ShipmentDetails OpenAPI object
     */
    private ShipmentDetails parseShipmentDetailsOpenApi(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), ShipmentDetails.class);
        } catch (Exception e) {
            log.error("Failed to parse ShipmentDetails JSONB", e);
            return null;
        }
    }

    /**
     * Parse JSONB to DeliveryAcknowledgement OpenAPI object
     */
    private DeliveryAcknowledgement parseDeliveryAckOpenApi(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), DeliveryAcknowledgement.class);
        } catch (Exception e) {
            log.error("Failed to parse DeliveryAcknowledgement JSONB", e);
            return null;
        }
    }

    /**
     * Parse JSONB to Party extraction DTO object
     */
    private com.tosspaper.models.extraction.dto.Party parseParty(JSONB jsonb) {
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
            return objectMapper.readValue(jsonb.data(),
                    new TypeReference<>() {});
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
    private com.tosspaper.generated.model.LineItem convertLineItem(LineItem extractionItem) {
        if (extractionItem == null) {
            return null;
        }

        com.tosspaper.generated.model.LineItem apiItem = new com.tosspaper.generated.model.LineItem();
        apiItem.setItemNo(extractionItem.getItemCode());
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

    /**
     * Parse JSONB to ShipmentDetails extraction DTO object
     */
    private com.tosspaper.models.extraction.dto.ShipmentDetails parseShipmentDetails(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), com.tosspaper.models.extraction.dto.ShipmentDetails.class);
        } catch (Exception e) {
            log.error("Failed to parse ShipmentDetails JSONB", e);
            return null;
        }
    }
    /**
     * Parse JSONB to DeliveryAcknowledgement extraction DTO object
     */
    private com.tosspaper.models.extraction.dto.DeliveryAcknowledgement parseDeliveryAck(JSONB jsonb) {
        try {
            return objectMapper.readValue(jsonb.data(), com.tosspaper.models.extraction.dto.DeliveryAcknowledgement.class);
        } catch (Exception e) {
            log.error("Failed to parse DeliveryAcknowledgement JSONB", e);
            return null;
        }
    }

    /**
     * Convert DeliverySlipsRecord to DeliverySlip domain model.
     */
    public com.tosspaper.models.domain.DeliverySlip toDomain(DeliverySlipsRecord record) {
        if (record == null) {
            return null;
        }
        return com.tosspaper.models.domain.DeliverySlip.builder()
                .documentNumber(record.getDocumentNumber())
                .documentDate(record.getDocumentDate())
                .poNumber(record.getPoNumber())
                .jobNumber(record.getJobNumber())
                .sellerInfo(parseParty(record.getSellerInfo()))
                .buyerInfo(parseParty(record.getBuyerInfo()))
                .shipToInfo(parseParty(record.getShipToInfo()))
                .billToInfo(parseParty(record.getBillToInfo()))
                .lineItems(parseLineItems(record.getLineItems()))
                .shipmentDetails(parseShipmentDetails(record.getShipmentDetails()))
                .deliveryAcknowledgement(parseDeliveryAck(record.getDeliveryAcknowledgement()))
                .companyId(record.getCompanyId())
                .projectId(record.getProjectId())
                .assignedId(record.getExtractionTaskId())
                .status(record.getStatus() != null 
                        ? findStatusByValue(record.getStatus()) 
                        : null)
                .build();
    }

    /**
     * Convert domain DeliverySlip to generated OpenAPI DeliverySlip.
     * Maps from domain model (extraction DTOs) to generated API model.
     */
    public DeliverySlip toDto(com.tosspaper.models.domain.DeliverySlip domain) {
        if (domain == null) {
            return null;
        }
        
        DeliverySlip dto = new DeliverySlip();
        dto.setDocumentNumber(domain.getDocumentNumber());
        dto.setDocumentDate(domain.getDocumentDate());
        dto.setPoNumber(domain.getPoNumber());
        dto.setJobNumber(domain.getJobNumber());
        dto.setProjectName(domain.getProjectName());
        dto.setDeliveryMethodNote(domain.getDeliveryMethodNote());
        
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
        
        // Convert ShipmentDetails from extraction DTO to generated
        if (domain.getShipmentDetails() != null) {
            dto.setShipmentDetails(convertShipmentDetails(domain.getShipmentDetails()));
        }
        
        // Convert DeliveryAcknowledgement from extraction DTO to generated
        if (domain.getDeliveryAcknowledgement() != null) {
            dto.setDeliveryAcknowledgement(convertDeliveryAck(domain.getDeliveryAcknowledgement()));
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
     * Convert ShipmentDetails from extraction DTO to generated OpenAPI model.
     */
    private ShipmentDetails convertShipmentDetails(com.tosspaper.models.extraction.dto.ShipmentDetails domain) {
        if (domain == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(domain, ShipmentDetails.class);
        } catch (Exception e) {
            log.error("Failed to convert ShipmentDetails to generated model", e);
            return null;
        }
    }

    /**
     * Convert DeliveryAcknowledgement from extraction DTO to generated OpenAPI model.
     */
    private DeliveryAcknowledgement convertDeliveryAck(com.tosspaper.models.extraction.dto.DeliveryAcknowledgement domain) {
        if (domain == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(domain, DeliveryAcknowledgement.class);
        } catch (Exception e) {
            log.error("Failed to convert DeliveryAcknowledgement to generated model", e);
            return null;
        }
    }

    /**
     * Convert domain Status enum to generated DeliverySlipStatus enum.
     */
    private DeliverySlipStatus convertStatus(com.tosspaper.models.domain.DeliverySlip.Status domainStatus) {
        if (domainStatus == null) {
            return null;
        }
        // Map domain status values to generated status values
        return switch (domainStatus) {
            case DRAFT -> DeliverySlipStatus.DRAFT;
            case DELIVERED -> DeliverySlipStatus.DELIVERED;
        };
    }

    private com.tosspaper.models.domain.DeliverySlip.Status findStatusByValue(String statusValue) {
        for (com.tosspaper.models.domain.DeliverySlip.Status status : com.tosspaper.models.domain.DeliverySlip.Status.values()) {
            if (status.getValue().equals(statusValue)) {
                return status;
            }
        }
        return null;
    }
}

