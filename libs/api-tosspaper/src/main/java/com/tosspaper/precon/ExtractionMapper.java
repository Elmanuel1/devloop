package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.Extraction;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring", uses = {ExtractionJsonConverter.class})
public interface ExtractionMapper {

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

    // ---- Named converters (simple type coercions — no ObjectMapper needed) ----

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
}
