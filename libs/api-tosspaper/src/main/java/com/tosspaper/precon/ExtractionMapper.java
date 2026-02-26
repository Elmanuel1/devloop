package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.Extraction;
import com.tosspaper.precon.generated.model.ExtractionError;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface ExtractionMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ---- Record → DTO ----

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "entityId", source = "entityId", qualifiedByName = "stringToUuid")
    @Mapping(target = "entityType", source = "entityType", qualifiedByName = "stringToEntityType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToExtractionStatus")
    @Mapping(target = "documentIds", source = "documentIds", qualifiedByName = "jsonbToUuidList")
    @Mapping(target = "requestedFields", source = "fieldNames", qualifiedByName = "jsonbToStringList")
    // started_at, completed_at, errors are NOT in the generated record — handled via afterMapping or ignored
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "errors", ignore = true)
    Extraction toDto(ExtractionsRecord record);

    List<Extraction> toDtoList(List<ExtractionsRecord> records);

    // ---- Named converters ----

    @Named("stringToUuid")
    default UUID stringToUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    @Named("stringToEntityType")
    default EntityType stringToEntityType(String entityType) {
        return entityType != null ? EntityType.fromValue(entityType) : null;
    }

    @Named("stringToExtractionStatus")
    default ExtractionStatus stringToExtractionStatus(String status) {
        return status != null ? ExtractionStatus.fromValue(status) : null;
    }

    @Named("jsonbToUuidList")
    default List<UUID> jsonbToUuidList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            List<String> strings = OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
            return strings.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("jsonbToStringList")
    default List<String> jsonbToStringList(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @Named("jsonbToErrorList")
    default List<ExtractionError> jsonbToErrorList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Named("stringListToJsonb")
    default JSONB stringListToJsonb(List<String> strings) {
        if (strings == null || strings.isEmpty()) return null;
        try {
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(strings));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize string list", e);
        }
    }

    @Named("uuidListToJsonb")
    default JSONB uuidListToJsonb(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return JSONB.valueOf("[]");
        try {
            List<String> strings = uuids.stream().map(UUID::toString).toList();
            return JSONB.valueOf(OBJECT_MAPPER.writeValueAsString(strings));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize UUID list", e);
        }
    }

    /**
     * Maps a record to DTO and additionally populates started_at, completed_at, errors
     * from the raw record using DSL.field access. This is necessary because those columns
     * were added in V3.5 after the jOOQ classes were generated.
     */
    default Extraction toDtoWithExtras(ExtractionsRecord record,
                                        OffsetDateTime startedAt,
                                        OffsetDateTime completedAt,
                                        List<ExtractionError> errors) {
        Extraction dto = toDto(record);
        dto.setStartedAt(startedAt);
        dto.setCompletedAt(completedAt);
        dto.setErrors(errors != null ? errors : List.of());
        return dto;
    }
}
