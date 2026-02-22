package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderAddress;
import com.tosspaper.generated.model.TenderBond;
import com.tosspaper.generated.model.TenderCondition;
import com.tosspaper.generated.model.TenderEvent;
import com.tosspaper.generated.model.TenderParty;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenderMapper {

    private final ObjectMapper objectMapper;

    public Tender toDto(TendersRecord record) {
        Tender tender = new Tender();
        tender.setId(UUID.fromString(record.getId()));
        tender.setName(record.getName());
        tender.setPlatform(record.getPlatform());
        tender.setStatus(record.getStatus() != null ? TenderStatus.fromValue(record.getStatus()) : null);
        tender.setCurrency(record.getCurrency());
        tender.setReferenceNumber(record.getReferenceNumber());
        tender.setScopeOfWork(record.getScopeOfWork());
        tender.setDeliveryMethod(record.getDeliveryMethod());
        tender.setClosingDate(record.getClosingDate());
        tender.setCompletionDate(record.getCompletionDate());
        tender.setInquiryDeadline(record.getInquiryDeadline());
        tender.setSubmissionMethod(record.getSubmissionMethod());
        tender.setSubmissionUrl(record.getSubmissionUrl());
        tender.setLiquidatedDamages(record.getLiquidatedDamages());
        tender.setCreatedAt(record.getCreatedAt());
        tender.setUpdatedAt(record.getUpdatedAt());
        tender.setCreatedBy(record.getCreatedBy());

        // Parse version from raw field (added in V1.47 migration)
        try {
            Object versionObj = record.get("version");
            if (versionObj instanceof Integer) {
                tender.setVersion((Integer) versionObj);
            } else if (versionObj instanceof Number) {
                tender.setVersion(((Number) versionObj).intValue());
            } else {
                tender.setVersion(0);
            }
        } catch (Exception e) {
            tender.setVersion(0);
        }

        // Parse JSONB fields
        if (record.getBonds() != null) {
            tender.setBonds(parseJsonbList(record.getBonds(), new TypeReference<List<TenderBond>>() {}));
        }
        if (record.getConditions() != null) {
            tender.setConditions(parseJsonbList(record.getConditions(), new TypeReference<List<TenderCondition>>() {}));
        }
        if (record.getParties() != null) {
            tender.setParties(parseJsonbList(record.getParties(), new TypeReference<List<TenderParty>>() {}));
        }
        if (record.getLocation() != null) {
            tender.setLocation(parseJsonb(record.getLocation(), TenderAddress.class));
        }
        if (record.getMetadata() != null) {
            tender.setMetadata(parseJsonb(record.getMetadata(), new TypeReference<Map<String, Object>>() {}));
        }

        // Parse events from raw field (added in V1.47 migration)
        try {
            Object eventsObj = record.get("events");
            if (eventsObj instanceof JSONB) {
                tender.setEvents(parseJsonbList((JSONB) eventsObj, new TypeReference<List<TenderEvent>>() {}));
            }
        } catch (Exception e) {
            log.debug("Could not parse events field", e);
        }

        // Parse start_date from raw field (added in V1.47 migration)
        try {
            Object startDateObj = record.get("start_date");
            if (startDateObj instanceof java.time.LocalDate) {
                tender.setStartDate((java.time.LocalDate) startDateObj);
            }
        } catch (Exception e) {
            log.debug("Could not parse start_date field", e);
        }

        return tender;
    }

    public List<Tender> toDtoList(List<TendersRecord> records) {
        return records.stream().map(this::toDto).toList();
    }

    /**
     * Get the version from a TendersRecord. Returns 0 if not accessible.
     */
    public int getVersion(TendersRecord record) {
        try {
            Object versionObj = record.get("version");
            if (versionObj instanceof Number) {
                return ((Number) versionObj).intValue();
            }
        } catch (Exception e) {
            log.debug("Could not read version field", e);
        }
        return 0;
    }

    private <T> T parseJsonb(JSONB jsonb, Class<T> clazz) {
        try {
            return objectMapper.readValue(jsonb.data(), clazz);
        } catch (Exception e) {
            log.warn("Failed to parse JSONB to {}", clazz.getSimpleName(), e);
            return null;
        }
    }

    private <T> T parseJsonb(JSONB jsonb, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(jsonb.data(), typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSONB", e);
            return null;
        }
    }

    private <T> List<T> parseJsonbList(JSONB jsonb, TypeReference<List<T>> typeRef) {
        try {
            return objectMapper.readValue(jsonb.data(), typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSONB list", e);
            return null;
        }
    }
}
