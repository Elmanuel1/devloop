package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tosspaper.models.domain.integration.ProviderTracked;
import com.tosspaper.models.extraction.dto.InvoiceDetails;
import com.tosspaper.models.extraction.dto.Party;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Domain model for Invoice.
 * Represents the business entity, not tied to database or API schemas.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Invoice extends ProviderTracked implements TossPaperEntity {
    public enum Status {
        PENDING("pending"),
        ACCEPTED("accepted"),
        ;

        @Getter
        final String value;
        Status(String status) {
            this.value = status;
        }

        public String fromValue(String status) {
            for (Status value: Status.values()) {
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
    private Party sellerInfo;
    private Party buyerInfo;
    private Party shipToInfo;
    private Party billToInfo;
    private Status status;
    private List<LineItem> lineItems;
    private InvoiceDetails invoiceDetails;
    private Long companyId;
    private String projectId;

    @JsonProperty("id")
    @JsonAlias("assignedId")
    private String assignedId;
    
    /**
     * Timestamp when this invoice was last successfully pushed to external provider.
     * Null if never synced. Used to track QuickBooks Bill sync.
     */
    private OffsetDateTime lastSyncAt;

    /**
     * Implementation of TossPaperEntity.getId() - returns assignedId.
     */
    @Override
    @JsonIgnore
    public String getId() {
        return assignedId;
    }
}
