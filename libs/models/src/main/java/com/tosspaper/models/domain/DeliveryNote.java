package com.tosspaper.models.domain;

import com.tosspaper.models.extraction.dto.DeliveryAcknowledgement;
import com.tosspaper.models.extraction.dto.Party;
import com.tosspaper.models.extraction.dto.ShipmentDetails;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * Domain model for Delivery Note.
 * Represents the business entity, not tied to database or API schemas.
 */
@Data
@Builder(toBuilder = true)
public class DeliveryNote {
    public enum Status {
        DRAFT("draft"),
        ACCEPTED("delivered");

        @Getter
        private final String value;
        Status(String status) {
            this.value = status;
        }

        public String fromValue(String status) {
            for (DeliveryNote.Status value: DeliveryNote.Status.values()) {
                if (value.value.equals(status)) {
                    return value.value;
                }
            }

            throw new IllegalArgumentException(status);
        }
    }
    private String documentNumber;
    private LocalDate documentDate;
    private String poNumber;
    private String jobNumber;
    private String projectName;
    private String deliveryMethodNote;

    // Party information (will be serialized to JSONB)
    private Party sellerInfo;
    private Party buyerInfo;
    private Party shipToInfo;
    private Party billToInfo;

    // Line items as List<Map> (will be serialized to JSONB)
    private List<LineItem> lineItems;

    // Delivery note specific fields
    private ShipmentDetails shipmentDetails;
    private DeliveryAcknowledgement deliveryAcknowledgement;
    private Status status;
    private Long companyId;
    private String projectId;
    private String assignedId;
}
