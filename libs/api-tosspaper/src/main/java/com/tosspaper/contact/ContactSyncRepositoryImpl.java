package com.tosspaper.contact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.Address;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PartyTag;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tosspaper.models.jooq.Tables.CONTACTS;
import static org.jooq.impl.DSL.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ContactSyncRepositoryImpl implements ContactSyncRepository {
    
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    
    @Override
    @SneakyThrows
    public void upsertFromProvider(Long companyId, List<Party> contacts) {
        if (contacts.isEmpty()) {
            return;
        }
        
        // Build batched CTE-based upsert queries
        // Uses WITH ... UPDATE ... RETURNING ... INSERT ... WHERE NOT EXISTS pattern
        // to handle conflicts on three unique constraints:
        // 1. (company_id, provider, external_id)
        // 2. (company_id, email, tag)
        // 3. (company_id, phone, tag)
        // Only targets provider-synced contacts (provider IS NOT NULL)
        List<org.jooq.Query> queries = contacts.stream()
            .<org.jooq.Query>map(contact -> {
                JSONB externalMetadataJson = mapToJsonb(contact.getExternalMetadata());
                JSONB addressJson = addressToJsonb(contact.getAddress());

                // Capture values for use in UPDATE and INSERT
                String contactName = contact.getName();
                String contactEmail = contact.getEmail();
                String contactPhone = contact.getPhone();
                String contactNotes = contact.getNotes();
                OffsetDateTime providerLastUpdatedAt = contact.getProviderLastUpdatedAt();
                String status = contact.getStatus() != null ? contact.getStatus().name().toLowerCase() : "active";
                String tag = contact.getTag() != null ? contact.getTag().getValue() : null;
                String externalId = contact.getExternalId();
                String currencyCode = contact.getCurrencyCode() != null ? contact.getCurrencyCode().getCode() : null;
                
                // Get provider from Party object and convert to lowercase for consistent storage
                String providerLower = contact.getProvider() != null ? contact.getProvider().toLowerCase() : null;
                
                var updated = name("updated");
                
                return dsl.with(updated)
                    .as(dsl.update(CONTACTS)
                    .set(CONTACTS.NAME, contactName)
                    .set(CONTACTS.EMAIL, contactEmail)
                    .set(CONTACTS.PHONE, contactPhone)
                    .set(CONTACTS.ADDRESS, addressJson)
                    .set(CONTACTS.NOTES, contactNotes)
                    .set(CONTACTS.CURRENCY_CODE, currencyCode)
                    .set(CONTACTS.EXTERNAL_METADATA, externalMetadataJson)
                    .set(CONTACTS.PROVIDER_VERSION, contact.getProviderVersion())
                    .set(CONTACTS.PROVIDER_LAST_UPDATED_AT, providerLastUpdatedAt)
                    .set(CONTACTS.STATUS, status)
                    .set(CONTACTS.TAG, tag)
                        .where(
                            // First condition: (company_id, provider, external_id) - only matches provider-synced contacts
                            (CONTACTS.COMPANY_ID.eq(companyId)
                                .and(CONTACTS.PROVIDER.eq(providerLower))
                                .and(CONTACTS.EXTERNAL_ID.eq(externalId)))
                            // OR second condition: (company_id, email, tag) - matches both provider-synced and local contacts
                            .or(CONTACTS.COMPANY_ID.eq(companyId)
                                .and(CONTACTS.EMAIL.eq(contactEmail))
                                .and(CONTACTS.TAG.eq(tag))
                                .and(CONTACTS.EMAIL.isNotNull()))
                            // OR third condition: (company_id, phone, tag) - matches both provider-synced and local contacts
                            .or(CONTACTS.COMPANY_ID.eq(companyId)
                                .and(CONTACTS.PHONE.eq(contactPhone))
                                .and(CONTACTS.TAG.eq(tag))
                                .and(CONTACTS.PHONE.isNotNull()))
                            // OR fourth condition: (company_id, name) - matches by unique name constraint
                            .or(CONTACTS.COMPANY_ID.eq(companyId)
                                .and(lower(trim(CONTACTS.NAME)).eq(lower(trim(val(contactName)))))
                                .and(CONTACTS.NAME.isNotNull()))
                        )
                        .returning())
                    .insertInto(CONTACTS,
                        CONTACTS.COMPANY_ID,
                        CONTACTS.PROVIDER,
                        CONTACTS.EXTERNAL_ID,
                        CONTACTS.NAME,
                        CONTACTS.EMAIL,
                        CONTACTS.PHONE,
                        CONTACTS.ADDRESS,
                        CONTACTS.NOTES,
                        CONTACTS.CURRENCY_CODE,
                        CONTACTS.EXTERNAL_METADATA,
                        CONTACTS.PROVIDER_VERSION,
                        CONTACTS.PROVIDER_CREATED_AT,
                        CONTACTS.PROVIDER_LAST_UPDATED_AT,
                        CONTACTS.STATUS,
                        CONTACTS.TAG)
                    .select(
                        select(
                            val(companyId),
                            val(providerLower),
                            val(externalId),
                            val(contactName),
                            val(contactEmail),
                            val(contactPhone),
                            val(addressJson),
                            val(contactNotes),
                            val(currencyCode),
                            val(externalMetadataJson),
                            val(contact.getProviderVersion()),
                            val(contact.getProviderCreatedAt()),
                            val(providerLastUpdatedAt),
                            val(status),
                            val(tag)
                        )
                        .whereNotExists(selectOne().from(updated))
                    );
            })
            .collect(Collectors.toList());
        
        // Execute batched upserts
        // Provider-synced records (provider IS NOT NULL): provider is master, always accept updates
        // Locally-created records won't match the WHERE clause (provider is NULL locally)
        int[] results = dsl.batch(queries).execute();
        log.debug("Batched upsert completed for {} contacts: {} statements executed", 
            contacts.size(), results.length);
    }
    
    
    private JSONB addressToJsonb(Address address) {
        if (address == null) {
            return null;
        }
        try {
            // Convert domain Address (camelCase) to API format (snake_case) for consistent DB storage
            Map<String, Object> addressMap = new HashMap<>();
            if (address.getAddress() != null) {
                addressMap.put("address", address.getAddress());
            }
            if (address.getCity() != null) {
                addressMap.put("city", address.getCity());
            }
            if (address.getCountry() != null) {
                addressMap.put("country", address.getCountry());
            }
            if (address.getStateOrProvince() != null) {
                addressMap.put("state_or_province", address.getStateOrProvince());
            }
            if (address.getPostalCode() != null) {
                addressMap.put("postal_code", address.getPostalCode());
            }
            String json = objectMapper.writeValueAsString(addressMap);
            return JSONB.valueOf(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize address to JSONB", e);
            return null;
        }
    }

    @Override
    public void updateSyncStatus(String contactId, String provider, String externalId, String providerVersion, OffsetDateTime providerLastUpdatedAt) {
        String normalizedProvider = provider == null ? null : provider.toLowerCase(Locale.ROOT);
        int updated = dsl.update(CONTACTS)
                .set(CONTACTS.PROVIDER, normalizedProvider)
                .set(CONTACTS.EXTERNAL_ID, externalId)
                .set(CONTACTS.PROVIDER_VERSION, providerVersion)
                .set(CONTACTS.PROVIDER_LAST_UPDATED_AT, providerLastUpdatedAt)
                .set(CONTACTS.LAST_SYNC_AT, OffsetDateTime.now()) // Track when we successfully pushed to provider
                // Reset retry tracking on successful push
                .set(CONTACTS.PUSH_RETRY_COUNT, 0)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, false)
                .set(CONTACTS.PUSH_FAILURE_REASON, (String) null)
                // DO NOT set UPDATED_AT - it represents last local modification, not sync time
                .where(CONTACTS.ID.eq(contactId))
                .execute();

        if (updated == 0) {
            log.warn("No contact found with id {} to update sync status", contactId);
        }
    }

    @Override
    public void batchUpdateSyncStatus(List<SyncStatusUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Build batch update queries
        var queries = updates.stream()
            .map(update -> {
                String normalizedProvider = update.provider() == null ? null : update.provider().toLowerCase(Locale.ROOT);
                return dsl.update(CONTACTS)
                    .set(CONTACTS.PROVIDER, normalizedProvider)
                    .set(CONTACTS.EXTERNAL_ID, update.externalId())
                    .set(CONTACTS.PROVIDER_VERSION, update.providerVersion())
                    .set(CONTACTS.PROVIDER_LAST_UPDATED_AT, update.providerLastUpdatedAt())
                    .set(CONTACTS.LAST_SYNC_AT, now)
                    .where(CONTACTS.ID.eq(update.id()));
            })
            .toList();

        // Execute as batch for performance
        int[] results = dsl.batch(queries).execute();

        log.debug("Batch updated sync status for {} contacts", results.length);
    }

    private JSONB mapToJsonb(java.util.Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return JSONB.jsonbOrNull(objectMapper.writeValueAsString(map));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize map to JSONB", e);
            return null;
        }
    }

    @Override
    public Party findById(String id) {
        var record = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.eq(id))
                .fetchOne();

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
        party.setProvider(record.getProvider());
        party.setExternalId(record.getExternalId());
        party.setProviderVersion(record.getProviderVersion());
        party.setProviderCreatedAt(record.getProviderCreatedAt());
        party.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());
        party.setCreatedAt(record.getCreatedAt());
        party.setUpdatedAt(record.getUpdatedAt());

        // Parse address from JSONB
        if (record.getAddress() != null) {
            try {
                Map<String, Object> addressMap = objectMapper.readValue(
                        record.getAddress().data(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                Address address = Address.builder()
                        .address((String) addressMap.get("address"))
                        .city((String) addressMap.get("city"))
                        .country((String) addressMap.get("country"))
                        .stateOrProvince((String) addressMap.get("state_or_province"))
                        .postalCode((String) addressMap.get("postal_code"))
                        .build();
                party.setAddress(address);
            } catch (Exception e) {
                log.warn("Failed to parse address JSONB for contact {}", id, e);
            }
        }

        // Parse external metadata from JSONB
        if (record.getExternalMetadata() != null) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(
                        record.getExternalMetadata().data(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                party.setExternalMetadata(metadata);
            } catch (Exception e) {
                log.warn("Failed to parse external metadata JSONB for contact {}", id, e);
            }
        }

        // Parse tag
        if (record.getTag() != null) {
            try {
                party.setTag(PartyTag.fromValue(record.getTag()));
            } catch (Exception e) {
                log.warn("Failed to parse tag for contact {}: {}", id, record.getTag());
            }
        }

        // Parse status
        if (record.getStatus() != null) {
            try {
                party.setStatus(Party.PartyStatus.valueOf(record.getStatus().toUpperCase()));
            } catch (Exception e) {
                log.warn("Failed to parse status for contact {}: {}", id, record.getStatus());
            }
        }

        return party;
    }

    @Override
    public List<Party> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.in(ids))
                .fetch()
                .stream()
                .map(record -> {
                    Party party = new Party();
                    party.setId(record.getId());
                    party.setCompanyId(record.getCompanyId());
                    party.setName(record.getName());
                    party.setEmail(record.getEmail());
                    party.setPhone(record.getPhone());
                    party.setNotes(record.getNotes());
                    party.setProvider(record.getProvider());
                    party.setExternalId(record.getExternalId());
                    party.setProviderVersion(record.getProviderVersion());
                    party.setProviderCreatedAt(record.getProviderCreatedAt());
                    party.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());
                    party.setCreatedAt(record.getCreatedAt());
                    party.setUpdatedAt(record.getUpdatedAt());

                    // Map retry tracking fields
                    party.setPushRetryCount(record.getPushRetryCount());
                    party.setPushRetryLastAttemptAt(record.getPushRetryLastAttemptAt());
                    party.setPushPermanentlyFailed(record.getPushPermanentlyFailed());
                    party.setPushFailureReason(record.getPushFailureReason());

                    // Parse address from JSONB
                    if (record.getAddress() != null) {
                        try {
                            Map<String, Object> addressMap = objectMapper.readValue(
                                    record.getAddress().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            Address address = Address.builder()
                                    .address((String) addressMap.get("address"))
                                    .city((String) addressMap.get("city"))
                                    .country((String) addressMap.get("country"))
                                    .stateOrProvince((String) addressMap.get("state_or_province"))
                                    .postalCode((String) addressMap.get("postal_code"))
                                    .build();
                            party.setAddress(address);
                        } catch (Exception e) {
                            log.warn("Failed to parse address JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse external metadata from JSONB
                    if (record.getExternalMetadata() != null) {
                        try {
                            Map<String, Object> metadata = objectMapper.readValue(
                                    record.getExternalMetadata().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            party.setExternalMetadata(metadata);
                        } catch (Exception e) {
                            log.warn("Failed to parse external metadata JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse tag
                    if (record.getTag() != null) {
                        try {
                            party.setTag(PartyTag.fromValue(record.getTag()));
                        } catch (Exception e) {
                            log.warn("Failed to parse tag for contact {}: {}", record.getId(), record.getTag());
                        }
                    }

                    // Parse status
                    if (record.getStatus() != null) {
                        try {
                            party.setStatus(Party.PartyStatus.valueOf(record.getStatus().toUpperCase()));
                        } catch (Exception e) {
                            log.warn("Failed to parse status for contact {}: {}", record.getId(), record.getStatus());
                        }
                    }

                    return party;
                })
                .toList();
    }

    @Override
    public List<Party> findNeedingPush(Long companyId, int limit, List<String> tags, int maxRetries) {
        var query = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(companyId))
                // Condition 1: Has local changes that haven't been pushed yet
                // - updated_at > last_sync_at: record was modified locally after our last successful push
                // - OR last_sync_at IS NULL: record has never been pushed to the provider
                .and(CONTACTS.UPDATED_AT.greaterThan(CONTACTS.LAST_SYNC_AT)
                    .or(CONTACTS.LAST_SYNC_AT.isNull()))
                // Condition 2: Our local changes are newer than the provider's version
                // - updated_at > provider_last_updated_at: our local data is newer than what's in the provider
                // - OR provider_last_updated_at IS NULL: provider doesn't have this record yet
                // This prevents pushing stale data that would overwrite newer provider changes
                .and(CONTACTS.UPDATED_AT.greaterThan(CONTACTS.PROVIDER_LAST_UPDATED_AT)
                    .or(CONTACTS.PROVIDER_LAST_UPDATED_AT.isNull()))
                // Condition 3: Not permanently failed and within retry limit
                .and(CONTACTS.PUSH_PERMANENTLY_FAILED.eq(false))
                .and(CONTACTS.PUSH_RETRY_COUNT.lessThan(maxRetries));

        // Apply tag filter if specified
        if (tags != null && !tags.isEmpty()) {
            query = query.and(CONTACTS.TAG.in(tags));
        }
        // If tags is null or empty, fetch all contacts regardless of tag

        var records = query.limit(limit).fetch();

        return records.stream()
                .map(record -> {
                    Party party = new Party();
                    party.setId(record.getId());
                    party.setCompanyId(record.getCompanyId());
                    party.setName(record.getName());
                    party.setEmail(record.getEmail());
                    party.setPhone(record.getPhone());
                    party.setNotes(record.getNotes());
                    party.setProvider(record.getProvider());
                    party.setExternalId(record.getExternalId());
                    party.setProviderVersion(record.getProviderVersion());
                    party.setProviderCreatedAt(record.getProviderCreatedAt());
                    party.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());
                    party.setCreatedAt(record.getCreatedAt());
                    party.setUpdatedAt(record.getUpdatedAt());

                    // Map retry tracking fields
                    party.setPushRetryCount(record.getPushRetryCount());
                    party.setPushRetryLastAttemptAt(record.getPushRetryLastAttemptAt());
                    party.setPushPermanentlyFailed(record.getPushPermanentlyFailed());
                    party.setPushFailureReason(record.getPushFailureReason());

                    // Parse address from JSONB
                    if (record.getAddress() != null) {
                        try {
                            Map<String, Object> addressMap = objectMapper.readValue(
                                    record.getAddress().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            Address address = Address.builder()
                                    .address((String) addressMap.get("address"))
                                    .city((String) addressMap.get("city"))
                                    .country((String) addressMap.get("country"))
                                    .stateOrProvince((String) addressMap.get("state_or_province"))
                                    .postalCode((String) addressMap.get("postal_code"))
                                    .build();
                            party.setAddress(address);
                        } catch (Exception e) {
                            log.warn("Failed to parse address JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse external metadata from JSONB
                    if (record.getExternalMetadata() != null) {
                        try {
                            Map<String, Object> metadata = objectMapper.readValue(
                                    record.getExternalMetadata().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            party.setExternalMetadata(metadata);
                        } catch (Exception e) {
                            log.warn("Failed to parse external metadata JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse tag
                    if (record.getTag() != null) {
                        try {
                            party.setTag(PartyTag.fromValue(record.getTag()));
                        } catch (Exception e) {
                            log.warn("Failed to parse tag for contact {}: {}", record.getId(), record.getTag());
                        }
                    }

                    // Parse status
                    if (record.getStatus() != null) {
                        try {
                            party.setStatus(Party.PartyStatus.valueOf(record.getStatus().toUpperCase()));
                        } catch (Exception e) {
                            log.warn("Failed to parse status for contact {}: {}", record.getId(), record.getStatus());
                        }
                    }

                    return party;
                })
                .toList();
    }

    @Override
    public List<Party> findByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return List.of();
        }

        return dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(companyId)
                        .and(CONTACTS.PROVIDER.eq(provider.toLowerCase()))
                        .and(CONTACTS.EXTERNAL_ID.in(externalIds)))
                .fetch()
                .stream()
                .map(record -> {
                    Party party = new Party();
                    party.setId(record.getId());
                    party.setCompanyId(record.getCompanyId());
                    party.setName(record.getName());
                    party.setEmail(record.getEmail());
                    party.setPhone(record.getPhone());
                    party.setNotes(record.getNotes());
                    party.setProvider(record.getProvider());
                    party.setExternalId(record.getExternalId());
                    party.setProviderVersion(record.getProviderVersion());
                    party.setProviderCreatedAt(record.getProviderCreatedAt());
                    party.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());
                    party.setCreatedAt(record.getCreatedAt());
                    party.setUpdatedAt(record.getUpdatedAt());

                    // Map retry tracking fields
                    party.setPushRetryCount(record.getPushRetryCount());
                    party.setPushRetryLastAttemptAt(record.getPushRetryLastAttemptAt());
                    party.setPushPermanentlyFailed(record.getPushPermanentlyFailed());
                    party.setPushFailureReason(record.getPushFailureReason());

                    // Parse address from JSONB
                    if (record.getAddress() != null) {
                        try {
                            Map<String, Object> addressMap = objectMapper.readValue(
                                    record.getAddress().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            Address address = Address.builder()
                                    .address((String) addressMap.get("address"))
                                    .city((String) addressMap.get("city"))
                                    .country((String) addressMap.get("country"))
                                    .stateOrProvince((String) addressMap.get("state_or_province"))
                                    .postalCode((String) addressMap.get("postal_code"))
                                    .build();
                            party.setAddress(address);
                        } catch (Exception e) {
                            log.warn("Failed to parse address JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse external metadata from JSONB
                    if (record.getExternalMetadata() != null) {
                        try {
                            Map<String, Object> metadata = objectMapper.readValue(
                                    record.getExternalMetadata().data(),
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                            party.setExternalMetadata(metadata);
                        } catch (Exception e) {
                            log.warn("Failed to parse external metadata JSONB for contact {}", record.getId(), e);
                        }
                    }

                    // Parse tag
                    if (record.getTag() != null) {
                        try {
                            party.setTag(PartyTag.fromValue(record.getTag()));
                        } catch (Exception e) {
                            log.warn("Failed to parse tag for contact {}: {}", record.getId(), record.getTag());
                        }
                    }

                    // Parse status
                    if (record.getStatus() != null) {
                        try {
                            party.setStatus(Party.PartyStatus.valueOf(record.getStatus().toUpperCase()));
                        } catch (Exception e) {
                            log.warn("Failed to parse status for contact {}: {}", record.getId(), record.getStatus());
                        }
                    }

                    // Parse currency code
                    if (record.getCurrencyCode() != null) {
                        try {
                            party.setCurrencyCode(com.tosspaper.models.domain.Currency.fromCode(record.getCurrencyCode()));
                        } catch (Exception e) {
                            log.warn("Failed to parse currency code for contact {}: {}", record.getId(), record.getCurrencyCode());
                        }
                    }

                    return party;
                })
                .toList();
    }

    @Override
    public void incrementRetryCount(String contactId, String errorMessage) {
        int updated = dsl.update(CONTACTS)
                .set(CONTACTS.PUSH_RETRY_COUNT, CONTACTS.PUSH_RETRY_COUNT.add(1))
                .set(CONTACTS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .set(CONTACTS.PUSH_FAILURE_REASON, errorMessage)
                .where(CONTACTS.ID.eq(contactId))
                .execute();

        if (updated == 0) {
            log.warn("No contact found with id {} to increment retry count", contactId);
        }
    }

    @Override
    public void markAsPermanentlyFailed(String contactId, String errorMessage) {
        int updated = dsl.update(CONTACTS)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, true)
                .set(CONTACTS.PUSH_FAILURE_REASON, errorMessage)
                .set(CONTACTS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .where(CONTACTS.ID.eq(contactId))
                .execute();

        if (updated == 0) {
            log.warn("No contact found with id {} to mark as permanently failed", contactId);
        }
    }

    @Override
    public void resetRetryTracking(String contactId) {
        int updated = dsl.update(CONTACTS)
                .set(CONTACTS.PUSH_RETRY_COUNT, 0)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, false)
                .set(CONTACTS.PUSH_FAILURE_REASON, (String) null)
                .set(CONTACTS.PUSH_RETRY_LAST_ATTEMPT_AT, (OffsetDateTime) null)
                .where(CONTACTS.ID.eq(contactId))
                .execute();

        if (updated == 0) {
            log.warn("No contact found with id {} to reset retry tracking", contactId);
        }
    }
}
