package com.tosspaper.integrations.quickbooks.vendor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.PhysicalAddress;
import com.intuit.ipp.data.TelephoneNumber;
import com.intuit.ipp.data.Vendor;
import com.tosspaper.models.domain.Address;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PartyTag;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.intuit.ipp.data.ReferenceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class VendorMapper {

    private final ObjectMapper objectMapper;

    public VendorMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Party toDomain(Vendor qboVendor) {
        if (qboVendor == null) {
            return null;
        }

        Party party = new Party();
        party.setExternalId(qboVendor.getId());
        party.setProviderVersion(qboVendor.getSyncToken());
        party.setName(qboVendor.getDisplayName());
        party.setAddress(mapAddress(qboVendor.getBillAddr()));

        if (qboVendor.getPrimaryPhone() != null) {
            party.setPhone(qboVendor.getPrimaryPhone().getFreeFormNumber());
        }

        if (party.getPhone() == null && qboVendor.getAlternatePhone() != null) {
            party.setPhone(qboVendor.getAlternatePhone().getFreeFormNumber());
        }

        if (party.getPhone() == null && qboVendor.getMobile() != null) {
            party.setPhone(qboVendor.getMobile().getFreeFormNumber());
        }

        if (qboVendor.getPrimaryEmailAddr() != null) {
            party.setEmail(qboVendor.getPrimaryEmailAddr().getAddress());
        }

        party.setNotes(qboVendor.getNotes());
        party.setTag(PartyTag.SUPPLIER);
        party.setStatus(qboVendor.isActive() ? Party.PartyStatus.ACTIVE : Party.PartyStatus.ARCHIVED);
        party.setProvider(IntegrationProvider.QUICKBOOKS.getValue()); // Set provider for synced entities

        // Map timestamps from QB MetaData for sync tracking
        if (qboVendor.getMetaData() != null) {
            if (qboVendor.getMetaData().getCreateTime() != null) {
                party.setProviderCreatedAt(
                        qboVendor.getMetaData().getCreateTime().toInstant().atOffset(java.time.ZoneOffset.UTC));
            }
            if (qboVendor.getMetaData().getLastUpdatedTime() != null) {
                party.setProviderLastUpdatedAt(
                        qboVendor.getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC));
            }
        }

        // Map currency from QBO CurrencyRef
        if (qboVendor.getCurrencyRef() != null && qboVendor.getCurrencyRef().getValue() != null) {
            party.setCurrencyCode(Currency.fromQboValue(qboVendor.getCurrencyRef().getValue()));
        }

        // Store the full QBO entity so we can merge updates without dropping fields.
        // Persisted as JSONB via external_metadata.
        try {
            Map<String, Object> externalMetadata = new HashMap<>();
            externalMetadata.put("qboEntity", objectMapper.writeValueAsString(qboVendor));
            party.setExternalMetadata(externalMetadata);
        } catch (Exception ignored) {
            // Best-effort; mapping should still succeed even if serialization fails.
        }

        return party;
    }

    /**
     * Convert Party to QBO Vendor.
     * First deserializes stored QBO entity from metadata (if exists) to preserve
     * QB-only fields,
     * then applies domain values on top. Handles both CREATE and UPDATE.
     */
    public Vendor toQboVendor(Party party) {
        if (party == null) {
            return null;
        }

        // Deserialize stored QBO entity (preserves TermRef, Balance, etc.)
        Vendor vendor = deserializeStoredQboEntity(party);

        // For UPDATE: set Id and SyncToken
        if (party.isUpdatable()) {
            vendor.setId(party.getExternalId());
            vendor.setSyncToken(party.getProviderVersion());
        }

        // Apply domain field changes
        applyDomainFieldsToVendor(vendor, party);

        return vendor;
    }

    /**
     * Apply domain model fields to a QBO Vendor.
     * Preserves existing BillAddr structure when updating (Line2, Line3, Id, etc.)
     */
    private void applyDomainFieldsToVendor(Vendor vendor, Party party) {
        vendor.setDisplayName(party.getName());

        if (party.getAddress() != null) {
            // Preserve existing BillAddr if updating, otherwise create new
            PhysicalAddress addr = vendor.getBillAddr();
            if (addr == null) {
                addr = new PhysicalAddress();
            }

            // Always update address fields from domain model (even if null to clear them)
            addr.setLine1(party.getAddress().getAddress());
            addr.setCity(party.getAddress().getCity());
            addr.setCountry(party.getAddress().getCountry());
            addr.setCountrySubDivisionCode(party.getAddress().getStateOrProvince());
            addr.setPostalCode(party.getAddress().getPostalCode());

            vendor.setBillAddr(addr);
        }

        if (party.getPhone() != null) {
            TelephoneNumber phone = new TelephoneNumber();
            phone.setFreeFormNumber(party.getPhone());
            vendor.setPrimaryPhone(phone);
        }

        if (party.getEmail() != null) {
            EmailAddress email = new EmailAddress();
            email.setAddress(party.getEmail());
            vendor.setPrimaryEmailAddr(email);
        }

        // Only set notes if not null and not blank (preserves existing notes in
        // QuickBooks)
        if (party.getNotes() != null && !party.getNotes().isBlank()) {
            vendor.setNotes(party.getNotes());
        }

        // Set currency (QBO only allows setting currency on vendor CREATE, not UPDATE)
        if (!party.isUpdatable() && party.getCurrencyCode() != null) {
            ReferenceType currencyRef = new ReferenceType();
            currencyRef.setValue(party.getCurrencyCode().getCode());
            vendor.setCurrencyRef(currencyRef);
            log.debug("Setting currency {} for new vendor CREATE: {}", party.getCurrencyCode().getCode(), party.getName());
        } else if (party.isUpdatable() && party.getCurrencyCode() != null) {
            // Log that currency cannot be updated for existing vendors (QuickBooks limitation)
            String currentQbCurrency = vendor.getCurrencyRef() != null ? vendor.getCurrencyRef().getValue() : "null";
            String desiredCurrency = party.getCurrencyCode().getCode();
            if (!desiredCurrency.equals(currentQbCurrency)) {
                log.warn("Cannot update currency for existing vendor {} (QuickBooks limitation). Current: {}, Desired: {}", 
                    party.getName(), currentQbCurrency, desiredCurrency);
            }
        }
    }

    private Vendor deserializeStoredQboEntity(Party party) {
        if (party.getExternalMetadata() == null) {
            return new Vendor();
        }
        Object qboEntityJson = party.getExternalMetadata().get("qboEntity");
        if (qboEntityJson == null) {
            return new Vendor();
        }
        try {
            return objectMapper.readValue(qboEntityJson.toString(), Vendor.class);
        } catch (Exception e) {
            return new Vendor();
        }
    }

    private Address mapAddress(PhysicalAddress qboAddress) {
        if (qboAddress == null) {
            return null;
        }
        return Address.builder()
                .address(combineLines(qboAddress))
                .city(qboAddress.getCity())
                .country(qboAddress.getCountry())
                .stateOrProvince(qboAddress.getCountrySubDivisionCode())
                .postalCode(qboAddress.getPostalCode())
                .build();
    }

    private String combineLines(PhysicalAddress addr) {
        StringBuilder sb = new StringBuilder();
        if (addr.getLine1() != null)
            sb.append(addr.getLine1());
        if (addr.getLine2() != null) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(addr.getLine2());
        }
        if (addr.getLine3() != null) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(addr.getLine3());
        }
        return sb.toString();
    }
}
