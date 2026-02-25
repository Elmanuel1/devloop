package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.ContentType;
import com.tosspaper.precon.generated.model.TenderDocument;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface TenderDocumentMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "stringToUuid")
    @Mapping(target = "tenderId", source = "tenderId", qualifiedByName = "stringToUuid")
    @Mapping(target = "contentType", source = "contentType", qualifiedByName = "stringToContentType")
    @Mapping(target = "status", source = "status", qualifiedByName = "stringToDocumentStatus")
    TenderDocument toDto(TenderDocumentsRecord record);

    List<TenderDocument> toDtoList(List<TenderDocumentsRecord> records);

    @Named("stringToUuid")
    default UUID stringToUuid(String id) {
        return id != null ? UUID.fromString(id) : null;
    }

    @Named("stringToContentType")
    default ContentType stringToContentType(String contentType) {
        if (contentType == null) return null;
        try {
            return ContentType.fromValue(contentType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Named("stringToDocumentStatus")
    default TenderDocumentStatus stringToDocumentStatus(String status) {
        if (status == null) return null;
        try {
            return TenderDocumentStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
