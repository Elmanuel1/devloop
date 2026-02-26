package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.exception.IfMatchRequiredException;
import com.tosspaper.models.exception.StaleVersionException;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.Extraction;
import com.tosspaper.precon.generated.model.ExtractionCreateRequest;
import com.tosspaper.precon.generated.model.ExtractionError;
import com.tosspaper.precon.generated.model.ExtractionField;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateRequest;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateResponse;
import com.tosspaper.precon.generated.model.ExtractionFieldListResponse;
import com.tosspaper.precon.generated.model.ExtractionListResponse;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import com.tosspaper.precon.generated.model.Pagination;
import com.tosspaper.precon.generated.model.TenderFieldName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionServiceImpl implements ExtractionService {

    private final ExtractionRepository extractionRepository;
    private final ExtractionFieldRepository extractionFieldRepository;
    private final TenderRepository tenderRepository;
    private final TenderDocumentRepository tenderDocumentRepository;
    private final ExtractionMapper extractionMapper;
    private final ExtractionFieldMapper extractionFieldMapper;
    private final ObjectMapper objectMapper;

    // Valid statuses for cancellation (any non-final, non-cancelled)
    private static final Set<String> CANCELLABLE_STATUSES = Set.of(
            ExtractionStatus.PENDING.getValue(),
            ExtractionStatus.PROCESSING.getValue());

    // Set of valid tender field names (derived from TenderFieldName enum)
    private static final Set<String> VALID_TENDER_FIELD_NAMES = Arrays.stream(TenderFieldName.values())
            .map(TenderFieldName::getValue)
            .collect(Collectors.toSet());

    @Override
    @Transactional
    public ExtractionResult createExtraction(Long companyId, ExtractionCreateRequest request) {
        String companyIdStr = companyId.toString();
        String entityIdStr = request.getEntityId().toString();

        TendersRecord tender = tenderRepository.findById(entityIdStr);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(ApiErrorMessages.TENDER_NOT_FOUND_CODE, ApiErrorMessages.TENDER_NOT_FOUND);
        }

        List<String> documentIdStrings = resolveDocumentIds(tender, request, entityIdStr);
        List<String> fieldNames = validateFieldNames(request.getFields(), "tender");

        ExtractionsRecord inserted = buildAndInsertRecord(
                companyIdStr, entityIdStr, documentIdStrings, fieldNames);

        Extraction dto = buildExtractionDto(inserted);
        return new ExtractionResult(dto, inserted.getVersion());
    }

    @Override
    public ExtractionListResponse listExtractions(Long companyId, UUID entityId,
                                                   ExtractionStatus status,
                                                   Integer limit, String cursor) {
        String companyIdStr = companyId.toString();
        String entityIdStr = entityId.toString();

        TendersRecord tender = tenderRepository.findById(entityIdStr);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(ApiErrorMessages.TENDER_NOT_FOUND_CODE, ApiErrorMessages.TENDER_NOT_FOUND);
        }

        int effectiveLimit = clampLimit(limit);
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        ExtractionQuery query = ExtractionQuery.builder()
                .entityId(entityIdStr)
                .status(status != null ? status.getValue() : null)
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorPair != null ? cursorPair.createdAt() : null)
                .cursorId(cursorPair != null ? cursorPair.id() : null)
                .build();

        List<ExtractionsRecord> records = extractionRepository.findByEntityId(entityIdStr, query);

        boolean hasMore = records.size() > effectiveLimit;
        if (hasMore) {
            records = records.subList(0, effectiveLimit);
        }

        List<Extraction> extractions = records.stream()
                .map(this::buildExtractionDto)
                .toList();

        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            ExtractionsRecord last = records.getLast();
            nextCursor = CursorUtils.encodeCursor(last.getCreatedAt(), last.getId());
        }

        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        ExtractionListResponse response = new ExtractionListResponse();
        response.setData(extractions);
        response.setPagination(pagination);

        return response;
    }

    @Override
    public ExtractionResult getExtraction(Long companyId, String extractionId) {
        ExtractionsRecord record = findExtractionForCompany(companyId.toString(), extractionId);
        Extraction dto = buildExtractionDto(record);
        return new ExtractionResult(dto, record.getVersion());
    }

    @Override
    @Transactional
    public void cancelExtraction(Long companyId, String extractionId) {
        ExtractionsRecord record = findExtractionForCompany(companyId.toString(), extractionId);

        // Idempotent: already cancelled → no-op
        if (ExtractionStatus.CANCELLED.getValue().equals(record.getStatus())) {
            return;
        }

        if (!CANCELLABLE_STATUSES.contains(record.getStatus())) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_CANNOT_CANCEL_CODE,
                    ApiErrorMessages.EXTRACTION_CANNOT_CANCEL.formatted(record.getStatus()));
        }

        extractionRepository.updateStatus(extractionId, ExtractionStatus.CANCELLED.getValue());
        extractionFieldRepository.deleteByExtractionId(extractionId);
    }

    @Override
    public ExtractionFieldListResponse listExtractionFields(Long companyId, String extractionId,
                                                             String fieldName, UUID documentId,
                                                             Integer limit, String cursor) {
        ExtractionsRecord extraction = findExtractionForCompany(companyId.toString(), extractionId);

        int effectiveLimit = clampLimit(limit);
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        ExtractionFieldQuery query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .fieldName(fieldName)
                .documentId(documentId != null ? documentId.toString() : null)
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorPair != null ? cursorPair.createdAt() : null)
                .cursorId(cursorPair != null ? cursorPair.id() : null)
                .build();

        List<ExtractionFieldsRecord> records = extractionFieldRepository.findByExtractionId(query);

        boolean hasMore = records.size() > effectiveLimit;
        if (hasMore) {
            records = records.subList(0, effectiveLimit);
        }

        EntityType entityType = EntityType.fromValue(extraction.getEntityType());
        UUID entityId = UUID.fromString(extraction.getEntityId());

        List<ExtractionField> fields = extractionFieldMapper.toDtoList(records, entityType, entityId);

        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            ExtractionFieldsRecord last = records.getLast();
            nextCursor = CursorUtils.encodeCursor(last.getCreatedAt(), last.getId());
        }

        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        ExtractionFieldListResponse response = new ExtractionFieldListResponse();
        response.setData(fields);
        response.setPagination(pagination);

        return response;
    }

    @Override
    @Transactional
    public ExtractionFieldBulkUpdateResponse bulkUpdateFields(Long companyId, String extractionId,
                                                               String ifMatch,
                                                               ExtractionFieldBulkUpdateRequest request) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException(
                    ApiErrorMessages.IF_MATCH_REQUIRED_CODE,
                    ApiErrorMessages.IF_MATCH_REQUIRED);
        }

        int expectedVersion = HeaderUtils.parseETagVersion(ifMatch);

        ExtractionsRecord extraction = findExtractionForCompany(companyId.toString(), extractionId);

        List<String> fieldIds = request.getUpdates().stream()
                .map(item -> item.getFieldId().toString())
                .toList();

        List<ExtractionFieldsRecord> existingFields = extractionFieldRepository.findAllByIds(fieldIds);

        Set<String> foundIds = existingFields.stream()
                .map(ExtractionFieldsRecord::getId)
                .collect(Collectors.toSet());

        for (String fieldId : fieldIds) {
            if (!foundIds.contains(fieldId)) {
                throw new NotFoundException(
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND);
            }
        }

        for (ExtractionFieldsRecord field : existingFields) {
            if (!field.getExtractionId().equals(extractionId)) {
                throw new NotFoundException(
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND);
            }
        }

        request.getUpdates().forEach(item -> {
            String fieldId = item.getFieldId().toString();
            JSONB editedValueJsonb = serializeObject(item.getEditedValue());
            extractionFieldRepository.updateEditedValue(fieldId, editedValueJsonb);
        });

        int rowsUpdated = extractionRepository.incrementVersion(extractionId, expectedVersion);
        if (rowsUpdated == 0) {
            throw new StaleVersionException(
                    ApiErrorMessages.EXTRACTION_STALE_VERSION_CODE,
                    ApiErrorMessages.EXTRACTION_STALE_VERSION);
        }

        List<ExtractionFieldsRecord> updatedFields = extractionFieldRepository.findAllByIds(fieldIds);
        List<ExtractionFieldsRecord> orderedFields = fieldIds.stream()
                .map(id -> updatedFields.stream()
                        .filter(f -> f.getId().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException(
                                ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND_CODE,
                                ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND)))
                .toList();

        EntityType entityType = EntityType.fromValue(extraction.getEntityType());
        UUID entityId = UUID.fromString(extraction.getEntityId());
        List<ExtractionField> dtos = extractionFieldMapper.toDtoList(orderedFields, entityType, entityId);

        ExtractionFieldBulkUpdateResponse response = new ExtractionFieldBulkUpdateResponse();
        response.setData(dtos);
        return response;
    }

    // ---- Private helpers ----

    private ExtractionsRecord findExtractionForCompany(String companyIdStr, String extractionId) {
        ExtractionsRecord record = extractionRepository.findById(extractionId);

        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(
                    ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                    ApiErrorMessages.EXTRACTION_NOT_FOUND);
        }

        return record;
    }

    private List<String> resolveDocumentIds(TendersRecord tender, ExtractionCreateRequest request,
                                             String entityIdStr) {
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            List<String> docIds = request.getDocumentIds().stream()
                    .map(UUID::toString)
                    .toList();
            for (String docId : docIds) {
                TenderDocumentsRecord doc = tenderDocumentRepository.findById(docId)
                        .orElseThrow(() -> new NotFoundException(
                                ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE,
                                ApiErrorMessages.DOCUMENT_NOT_FOUND));
                if (!doc.getTenderId().equals(entityIdStr)) {
                    throw new BadRequestException(
                            ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED_CODE,
                            ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED.formatted(docId, entityIdStr));
                }
                if (!"ready".equals(doc.getStatus())) {
                    throw new BadRequestException(
                            ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE,
                            ApiErrorMessages.EXTRACTION_NO_READY_DOCS.formatted(entityIdStr));
                }
            }
            return docIds;
        }

        List<TenderDocumentsRecord> readyDocs = tenderDocumentRepository.findByTenderId(
                entityIdStr, "ready", 200, null, null);
        if (readyDocs.isEmpty()) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE,
                    ApiErrorMessages.EXTRACTION_NO_READY_DOCS.formatted(entityIdStr));
        }
        return readyDocs.stream()
                .map(TenderDocumentsRecord::getId)
                .toList();
    }

    private List<String> validateFieldNames(List<String> fields, String entityType) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        for (String fieldName : fields) {
            if (!VALID_TENDER_FIELD_NAMES.contains(fieldName)) {
                throw new BadRequestException(
                        ApiErrorMessages.EXTRACTION_INVALID_FIELD_CODE,
                        ApiErrorMessages.EXTRACTION_INVALID_FIELD.formatted(fieldName, entityType));
            }
        }
        return fields;
    }

    private ExtractionsRecord buildAndInsertRecord(String companyIdStr, String entityIdStr,
                                                    List<String> documentIdStrings,
                                                    List<String> fieldNames) {
        String extractionId = UUID.randomUUID().toString();
        JSONB documentIdsJsonb = serializeStringList(documentIdStrings);
        JSONB fieldNamesJsonb = fieldNames != null ? serializeStringList(fieldNames) : null;

        ExtractionsRecord record = new ExtractionsRecord();
        record.setId(extractionId);
        record.setCompanyId(companyIdStr);
        record.setEntityType(EntityType.TENDER.getValue());
        record.setEntityId(entityIdStr);
        record.setStatus(ExtractionStatus.PENDING.getValue());
        record.setDocumentIds(documentIdsJsonb);
        record.setFieldNames(fieldNamesJsonb);
        record.setVersion(0);
        record.setCreatedBy(companyIdStr);

        return extractionRepository.insert(record);
    }

    /**
     * Build an Extraction DTO including the three V3.5 columns (started_at, completed_at, errors)
     * which are not in the generated jOOQ record — accessed via DSL.field on the raw record.
     */
    private Extraction buildExtractionDto(ExtractionsRecord record) {
        // Access V3.5 columns via DSL.field since they are not in the generated record
        OffsetDateTime startedAt = record.get(
                DSL.field("started_at", OffsetDateTime.class));
        OffsetDateTime completedAt = record.get(
                DSL.field("completed_at", OffsetDateTime.class));
        JSONB errorsJsonb = record.get(
                DSL.field("errors", JSONB.class));

        List<ExtractionError> errors = parseErrors(errorsJsonb);

        Extraction dto = extractionMapper.toDto(record);
        dto.setStartedAt(startedAt);
        dto.setCompletedAt(completedAt);
        dto.setErrors(errors);
        return dto;
    }

    private List<ExtractionError> parseErrors(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return objectMapper.readValue(jsonb.data(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private JSONB serializeStringList(List<String> strings) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(strings));
        } catch (Exception e) {
            throw new RuntimeException(ApiErrorMessages.SERIALIZATION_ERROR, e);
        }
    }

    private JSONB serializeObject(Object obj) {
        if (obj == null) return null;
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(obj));
        } catch (Exception e) {
            throw new RuntimeException(ApiErrorMessages.SERIALIZATION_ERROR, e);
        }
    }

    private int clampLimit(Integer limit) {
        int effective = limit != null ? limit : 20;
        if (effective < 1 || effective > 100) {
            effective = 20;
        }
        return effective;
    }
}
