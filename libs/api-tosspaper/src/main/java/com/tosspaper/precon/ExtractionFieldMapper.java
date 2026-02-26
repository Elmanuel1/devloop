package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.ExtractionField;
import com.tosspaper.precon.generated.model.FieldType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring", uses = {ExtractionJsonConverter.class})
public interface ExtractionFieldMapper {

    // ---- Record → DTO (without entity context) ----
    // entity_type and entity_id come from parent extraction, mapped separately

    @Mapping(target = "id", source = "record.id", qualifiedByName = "stringToUuid")
    @Mapping(target = "extractionId", source = "record.extractionId", qualifiedByName = "stringToUuid")
    @Mapping(target = "fieldName", source = "record.fieldName")
    @Mapping(target = "fieldType", source = "record.fieldType", qualifiedByName = "stringToFieldType")
    @Mapping(target = "proposedValue", source = "record.proposedValue", qualifiedByName = "jsonbToObject")
    @Mapping(target = "editedValue", source = "record.editedValue", qualifiedByName = "jsonbToObject")
    @Mapping(target = "confidence", source = "record.confidence")
    @Mapping(target = "citations", source = "record.citations", qualifiedByName = "jsonbToCitationList")
    @Mapping(target = "hasConflict", source = "record.hasConflict")
    @Mapping(target = "competingValues", source = "record.competingValues", qualifiedByName = "jsonbToCompetingValueList")
    @Mapping(target = "createdAt", source = "record.createdAt")
    @Mapping(target = "updatedAt", source = "record.updatedAt")
    @Mapping(target = "entityType", source = "entityType")
    @Mapping(target = "entityId", source = "entityId")
    ExtractionField toDto(ExtractionFieldsRecord record, EntityType entityType, UUID entityId);

    default List<ExtractionField> toDtoList(List<ExtractionFieldsRecord> records, EntityType entityType, UUID entityId) {
        return records.stream()
                .map(r -> toDto(r, entityType, entityId))
                .toList();
    }

    // ---- Named converters (simple type coercions — no ObjectMapper needed) ----

    @Named("stringToUuid")
    default UUID stringToUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    @Named("stringToFieldType")
    default FieldType stringToFieldType(String fieldType) {
        return fieldType != null ? FieldType.fromValue(fieldType) : null;
    }
}
