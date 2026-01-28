package com.tosspaper.contact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.query.PaginatedResult;
import com.tosspaper.generated.model.Contact;
import com.tosspaper.generated.model.ContactCreate;
import com.tosspaper.generated.model.ContactList;
import com.tosspaper.generated.model.ContactUpdate;
import com.tosspaper.generated.model.ContactTagEnum;
import com.tosspaper.generated.model.Address;
import com.tosspaper.generated.model.Pagination;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PartyTag;
import com.tosspaper.models.jooq.tables.records.ContactsRecord;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;
import com.tosspaper.generated.model.ContactStatus;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ContactMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mapping(target = "tag", source = "tag", qualifiedByName = "stringToTagEnum")
    @Mapping(target = "status", qualifiedByName = "stringToStatus")
    @Mapping(target = "address", source = "address", qualifiedByName = "jsonbToAddress")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "currencyCode", source = "currencyCode", qualifiedByName = "stringToCurrency")
    Contact toDto(ContactsRecord record);

    List<Contact> toDto(List<ContactsRecord> records);

    default ContactList toContactList(PaginatedResult<ContactsRecord> paginatedResult) {
        ContactList contactList = new ContactList();
        contactList.setData(toDto(paginatedResult.getData()));
        
        Pagination pagination = new Pagination();
        pagination.setPage(paginatedResult.getPage());
        pagination.setPageSize(paginatedResult.getPageSize());
        pagination.setTotalItems(paginatedResult.getTotal());
        pagination.setTotalPages((int) Math.ceil((double) paginatedResult.getTotal() / paginatedResult.getPageSize()));
        
        contactList.setPagination(pagination);
        return contactList;
    }

    @Mapping(target = "tag", source = "contactCreate.tag", qualifiedByName = "tagEnumToString")
    @Mapping(target = "address", source = "contactCreate.address", qualifiedByName = "addressToJsonb")
    @Mapping(target = "status", constant = "active")
    @Mapping(target = "email", source = "contactCreate.email", qualifiedByName = "emptyToNull")
    @Mapping(target = "phone", source = "contactCreate.phone", qualifiedByName = "emptyToNull")
    @Mapping(target = "currencyCode", source = "contactCreate.currencyCode", qualifiedByName = "emptyToNull")
    ContactsRecord toRecord(Long companyId, ContactCreate contactCreate);

    @Mapping(target = "tag", source = "tag", qualifiedByName = "tagEnumToString")
    @Mapping(target = "address", source = "address", qualifiedByName = "addressToJsonb", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "status", source = "status", qualifiedByName = "statusToString")
    @Mapping(target = "email", source = "email", qualifiedByName = "emptyToNull")
    @Mapping(target = "phone", source = "phone", qualifiedByName = "emptyToNull")
    @Mapping(target = "notes", source = "notes", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "currencyCode", source = "currencyCode", qualifiedByName = "currencyToString", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateRecord(ContactUpdate contactUpdate, @MappingTarget ContactsRecord record);

    default String jsonbToString(JSONB jsonb) {
        return jsonb == null ? null : jsonb.data();
    }

    default JSONB stringToJsonb(String string) {
        return string == null ? null : JSONB.valueOf(string);
    }

    @Named("jsonbToList")
    default List<ContactTagEnum> jsonbToList(String jsonb) {
        if (jsonb == null || jsonb.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> tags = OBJECT_MAPPER.readValue(jsonb, new TypeReference<>() {});
            return tags.stream().map(ContactTagEnum::fromValue).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse tags from JSONB", e);
        }
    }

    @Named("listToJsonb")
    default String listToJsonb(List<ContactTagEnum> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            List<String> stringTags = tags.stream().map(ContactTagEnum::getValue).collect(Collectors.toList());
            return OBJECT_MAPPER.writeValueAsString(stringTags);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse tags to JSONB", e);
        }
    }

    @Named("stringToStatus")
    default ContactStatus stringToStatus(String status) {
        return ContactStatus.fromValue(status);
    }

    @Named("jsonbToAddress")
    default Address jsonbToAddress(JSONB jsonb) {
        if (jsonb == null || jsonb.data().isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), Address.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse address from JSONB", e);
        }
    }

    @Named("addressToJsonb")
    default JSONB addressToJsonb(Address address) {
        if (address == null) {
            return null;
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(address);
            return JSONB.valueOf(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize address to JSONB", e);
        }
    }

    @Named("stringToTagEnum")
    default ContactTagEnum stringToTagEnum(String tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            return ContactTagEnum.fromValue(tag);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse tag from string", e);
        }
    }

    @Named("tagEnumToString")
    default String tagEnumToString(ContactTagEnum tag) {
        return tag == null ? null : tag.getValue();
    }
    
    @Named("statusToString")
    default String statusToString(ContactStatus status) {
        return status == null ? null : status.getValue();
    }
    
    @Named("emptyToNull")
    default String emptyToNull(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value;
    }
    
    @Named("stringToCurrency")
    default Currency stringToCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return null;
        }
        return Currency.fromCode(currencyCode);
    }
    
    @Named("currencyToString")
    default String currencyToString(Currency currency) {
        return currency == null ? null : currency.getCode();
    }
    
    /**
     * Convert ContactsRecord to a Party domain model.
     * Used for integration push events.
     */
    default Party toParty(ContactsRecord record) {
        if (record == null) {
            return null;
        }
        
        Party party = new Party();
        party.setId(record.getId());
        party.setCompanyId(record.getCompanyId());
        party.setName(record.getName());
        party.setEmail(record.getEmail());
        party.setPhone(record.getPhone());
        party.setNotes(record.getNotes());
        
        // Map tag - convert from string to PartyTag
        if (record.getTag() != null) {
            try {
                ContactTag tag = ContactTag.fromValue(record.getTag());
                if (tag == ContactTag.SUPPLIER) {
                    party.setTag(PartyTag.SUPPLIER);
                } else if (tag == ContactTag.SHIP_TO) {
                    party.setTag(PartyTag.SHIP_TO);
                }
            } catch (Exception e) {
                // Invalid tag, skip
            }
        }
        
        // Map status - convert from string to PartyStatus
        if (record.getStatus() != null) {
            String status = record.getStatus();
            if ("active".equalsIgnoreCase(status)) {
                party.setStatus(Party.PartyStatus.ACTIVE);
            } else if ("archived".equalsIgnoreCase(status)) {
                party.setStatus(Party.PartyStatus.ARCHIVED);
            }
        }
        
        // Map address - convert from JSONB to domain Address
        // Manual mapping handles snake_case to camelCase conversion
        if (record.getAddress() != null) {
            try {
                String addressJson = record.getAddress().data();
                if (!addressJson.trim().isEmpty()) {
                    // Parse as Map and map fields manually (handles snake_case fields)
                    Map<String, Object> addressMap = OBJECT_MAPPER.readValue(addressJson, new TypeReference<Map<String, Object>>() {});
                    if (addressMap != null && !addressMap.isEmpty()) {
                        com.tosspaper.models.domain.Address address = com.tosspaper.models.domain.Address.builder()
                                .address((String) addressMap.get("address"))
                                .city((String) addressMap.get("city"))
                                .country((String) addressMap.get("country"))
                                .stateOrProvince((String) addressMap.get("state_or_province"))
                                .postalCode((String) addressMap.get("postal_code"))
                                .build();
                        party.setAddress(address);
                    }
                }
            } catch (Exception e) {
                // Address deserialization failed - address will remain null
            }
        }
        
        // Map currency
        if (record.getCurrencyCode() != null) {
            party.setCurrencyCode(Currency.fromCode(record.getCurrencyCode()));
        }
        
        // Map provider tracking fields - critical for determining CREATE vs UPDATE
        party.setProvider(record.getProvider());
        party.setExternalId(record.getExternalId());
        party.setProviderVersion(record.getProviderVersion());
        
        // Map external metadata (contains stored QBO entity for merge updates)
        if (record.getExternalMetadata() != null) {
            try {
                String metadataJson = record.getExternalMetadata().data();
                if (!metadataJson.trim().isEmpty()) {
                    Map<String, Object> externalMetadata = OBJECT_MAPPER.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                    party.setExternalMetadata(externalMetadata);
                }
            } catch (IOException e) {
                // Log warning but continue without metadata
                // Note: Can't use logger in MapStruct default method, so we'll let the caller handle logging
            }
        }
        
        return party;
    }
} 