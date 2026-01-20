package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.tosspaper.models.domain.integration.ProviderTracked;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * Contact
 */

@Data
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Party extends ProviderTracked implements TossPaperEntity, Serializable {
    public enum PartyStatus {
        ACTIVE("active"),
        ARCHIVED("archived");

        private final String value;

        PartyStatus(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static PartyStatus fromValue(String value) {
            for (PartyStatus status : PartyStatus.values()) {
                if (status.value.equalsIgnoreCase(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }
  private String id;
  private Long companyId;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime createdAt;

  private OffsetDateTime updatedAt;

  private String name;

  private Address address;

  private String notes;

  private String phone;

  private String email;

  private PartyTag tag;

  private PartyStatus status;

  private String role;

  private Currency currencyCode;

  // externalId inherited from ProviderTracked

  public Party id(String id) {
    this.id = id;
    return this;
  }
}

