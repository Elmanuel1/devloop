package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.precon.generated.model.Citation;
import com.tosspaper.precon.generated.model.CompetingValue;
import com.tosspaper.precon.generated.model.ExtractionError;
import lombok.RequiredArgsConstructor;
import org.jooq.JSONB;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Spring-managed converter for JSONB ↔ typed value conversions used by
 * ExtractionMapper and ExtractionFieldMapper. Uses the Spring-managed
 * ObjectMapper so Jackson modules (JavaTimeModule, etc.) are configured once.
 */
@Component
@RequiredArgsConstructor
public class ExtractionJsonConverter {

    private final ObjectMapper objectMapper;

    @Named("jsonbToUuidList")
    public List<UUID> jsonbToUuidList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            List<String> strings = objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
            return strings.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("jsonbToStringList")
    public List<String> jsonbToStringList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("jsonbToErrorList")
    public List<ExtractionError> jsonbToErrorList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("jsonbToObject")
    public Object jsonbToObject(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return objectMapper.readValue(jsonb.data(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToCitationList")
    public List<Citation> jsonbToCitationList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("jsonbToCompetingValueList")
    public List<CompetingValue> jsonbToCompetingValueList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("stringListToJsonb")
    public JSONB stringListToJsonb(List<String> strings) throws Exception {
        if (strings == null || strings.isEmpty()) return null;
        return JSONB.valueOf(objectMapper.writeValueAsString(strings));
    }

    @Named("uuidListToJsonb")
    public JSONB uuidListToJsonb(List<UUID> uuids) throws Exception {
        if (uuids == null || uuids.isEmpty()) return JSONB.valueOf("[]");
        List<String> strings = uuids.stream().map(UUID::toString).toList();
        return JSONB.valueOf(objectMapper.writeValueAsString(strings));
    }

    @Named("objectToJsonb")
    public JSONB objectToJsonb(Object obj) throws Exception {
        if (obj == null) return null;
        return JSONB.valueOf(objectMapper.writeValueAsString(obj));
    }
}
