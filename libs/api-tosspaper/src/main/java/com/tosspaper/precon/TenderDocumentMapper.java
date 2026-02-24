package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tosspaper.precon.generated.model.ContentType;
import com.tosspaper.precon.generated.model.TenderDocument;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import org.jooq.JSONB;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TenderDocumentMapper {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ---- Record -> DTO ----

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "tenderId", source = "tenderId", qualifiedByName = "stringToUuid")
    @Mapping(target = "contentType", source = "contentType", qualifiedByName = "stringToContentType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToDocumentStatus")
    @Mapping(target = "fileSize", source = "fileSize", qualifiedByName = "longToInteger")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "jsonbToMap")
    TenderDocument toDto(TenderDocumentsRecord record);

    List<TenderDocument> toDtoList(List<TenderDocumentsRecord> records);

    // ---- Named converters ----

    @Named("stringToUuid")
    default UUID stringToUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    @Named("stringToContentType")
    default ContentType stringToContentType(String contentType) {
        return contentType != null ? ContentType.fromValue(contentType) : null;
    }

    @Named("stringToDocumentStatus")
    default TenderDocumentStatus stringToDocumentStatus(String status) {
        return status != null ? TenderDocumentStatus.fromValue(status) : null;
    }

    @Named("longToInteger")
    default Integer longToInteger(Long value) {
        return value != null ? value.intValue() : null;
    }

    @Named("jsonbToMap")
    default Object jsonbToMap(JSONB jsonb) {
        if (jsonb == null) return null;
        try {
            return OBJECT_MAPPER.readValue(jsonb.data(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
