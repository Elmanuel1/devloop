package com.tosspaper.integrations.quickbooks.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.CustomField;
import com.intuit.ipp.data.CustomFieldTypeEnum;
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.PhysicalAddress;
import com.intuit.ipp.data.TelephoneNumber;
import com.tosspaper.models.domain.Address;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PartyTag;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CustomerMapper {

    private static final String JOB_LOCATION_MARKER = "[Job Location] ";

    private final ObjectMapper objectMapper;

    public CustomerMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Party toDomain(Customer qboCustomer) {
        if (qboCustomer == null) {
            return null;
        }

        // Check if this QB Customer is marked as a job location
        // If not marked, skip it (it's a regular customer, not our ship-to location)
        if (!isJobLocation(qboCustomer)) {
            log.debug("Skipping QB Customer {} - not marked as job location", qboCustomer.getId());
            return null;
        }

        Party party = new Party();
        party.setExternalId(qboCustomer.getId());
        party.setProviderVersion(qboCustomer.getSyncToken());

        // Strip [Job Location] prefix from name when reading from QuickBooks
        String name = qboCustomer.getDisplayName();
        if (name != null && name.startsWith(JOB_LOCATION_MARKER)) {
            name = name.substring(JOB_LOCATION_MARKER.length());
        }
        party.setName(name);
        party.setAddress(mapAddress(qboCustomer.getBillAddr()));

        if (qboCustomer.getPrimaryPhone() != null) {
            party.setPhone(qboCustomer.getPrimaryPhone().getFreeFormNumber());
        }

        if (party.getPhone() == null && qboCustomer.getAlternatePhone() != null) {
            party.setPhone(qboCustomer.getAlternatePhone().getFreeFormNumber());
        }

        if (party.getPhone() == null && qboCustomer.getMobile() != null) {
            party.setPhone(qboCustomer.getMobile().getFreeFormNumber());
        }

        if (qboCustomer.getPrimaryEmailAddr() != null) {
            party.setEmail(qboCustomer.getPrimaryEmailAddr().getAddress());
        }

        party.setNotes(qboCustomer.getNotes());
        party.setTag(PartyTag.SHIP_TO);
        party.setStatus(qboCustomer.isActive() ? Party.PartyStatus.ACTIVE : Party.PartyStatus.ARCHIVED);
        party.setProvider(IntegrationProvider.QUICKBOOKS.getValue()); // Set provider for synced entities

        // Map timestamps from QB MetaData for sync tracking
        if (qboCustomer.getMetaData() != null) {
            if (qboCustomer.getMetaData().getCreateTime() != null) {
                party.setProviderCreatedAt(
                        qboCustomer.getMetaData().getCreateTime().toInstant().atOffset(java.time.ZoneOffset.UTC));
            }
            if (qboCustomer.getMetaData().getLastUpdatedTime() != null) {
                party.setProviderLastUpdatedAt(
                        qboCustomer.getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC));
            }
        }

        // Store the full QBO entity so we can merge updates without dropping fields.
        // Persisted as JSONB via external_metadata.
        try {
            Map<String, Object> externalMetadata = new HashMap<>();
            externalMetadata.put("qboEntity", objectMapper.writeValueAsString(qboCustomer));
            party.setExternalMetadata(externalMetadata);
        } catch (Exception ignored) {
            // Best-effort; mapping should still succeed even if serialization fails.
        }

        return party;
    }

    /**
     * Convert Party to QBO Customer.
     * First deserializes stored QBO entity from metadata (if exists) to preserve
     * QB-only fields,
     * then applies domain values on top. Handles both CREATE and UPDATE.
     */
    public Customer toQboCustomer(Party party) {
        if (party == null) {
            return null;
        }

        // Deserialize stored QBO entity (preserves Balance, etc.)
        Customer customer = deserializeStoredQboEntity(party);

        // For UPDATE: set Id and SyncToken
        if (party.isUpdatable()) {
            customer.setId(party.getExternalId());
            customer.setSyncToken(party.getProviderVersion());
        }

        // Apply domain field changes
        applyDomainFieldsToCustomer(customer, party);

        return customer;
    }

    /**
     * Apply domain model fields to a QBO Customer.
     * Preserves existing BillAddr structure when updating (Line2, Line3, Id, etc.)
     */
    private void applyDomainFieldsToCustomer(Customer customer, Party party) {
        // Add [Job Location] prefix to mark this as a ship-to location in QuickBooks
        String displayName = party.getName();
        if (displayName != null && !displayName.startsWith(JOB_LOCATION_MARKER)) {
            displayName = JOB_LOCATION_MARKER + displayName;
        }
        customer.setDisplayName(displayName);

        if (party.getAddress() != null) {
            // Preserve existing BillAddr if updating, otherwise create new
            PhysicalAddress addr = customer.getBillAddr();
            if (addr == null) {
                addr = new PhysicalAddress();
            }

            // Always update address fields from domain model (even if null to clear them)
            addr.setLine1(party.getAddress().getAddress());
            addr.setCity(party.getAddress().getCity());
            addr.setCountry(party.getAddress().getCountry());
            addr.setCountrySubDivisionCode(party.getAddress().getStateOrProvince());
            addr.setPostalCode(party.getAddress().getPostalCode());

            customer.setBillAddr(addr);
        }

        if (party.getPhone() != null) {
            TelephoneNumber phone = new TelephoneNumber();
            phone.setFreeFormNumber(party.getPhone());
            customer.setPrimaryPhone(phone);
        }

        if (party.getEmail() != null) {
            EmailAddress email = new EmailAddress();
            email.setAddress(party.getEmail());
            customer.setPrimaryEmailAddr(email);
        }

        // Only set notes if not null and not blank (preserves existing notes in QuickBooks)
        if (party.getNotes() != null && !party.getNotes().isBlank()) {
            customer.setNotes(party.getNotes());
        }
    }

    private Customer deserializeStoredQboEntity(Party party) {
        if (party.getExternalMetadata() == null) {
            return new Customer();
        }
        Object qboEntityJson = party.getExternalMetadata().get("qboEntity");
        if (qboEntityJson == null) {
            return new Customer();
        }
        try {
            return objectMapper.readValue(qboEntityJson.toString(), Customer.class);
        } catch (Exception e) {
            return new Customer();
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

    /**
     * Check if a QB Customer is marked as a job location.
     * Uses name prefix "[Job Location] " to identify ship-to locations.
     *
     * @param customer the QB Customer entity
     * @return true if marked as job location, false otherwise
     */
    private boolean isJobLocation(Customer customer) {
        return customer.getDisplayName() != null &&
               customer.getDisplayName().startsWith(JOB_LOCATION_MARKER);
    }
}
