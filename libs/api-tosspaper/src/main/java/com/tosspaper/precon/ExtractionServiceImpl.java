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
import com.tosspaper.precon.generated.model.ExtractionFieldUpdateItem;
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
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExtractionServiceImpl implements ExtractionService {

    private final ExtractionRepository extractionRepository;
    private final ExtractionFieldRepository extractionFieldRepository;
    private final ExtractionMapper extractionMapper;
    private final ExtractionFieldMapper extractionFieldMapper;
    private final ObjectMapper objectMapper;
    private final Map<EntityType, EntityExtractionAdapter> adapterMap;

    // Valid statuses for cancellation (any non-final, non-cancelled)
    private static final Set<String> CANCELLABLE_STATUSES = Set.of(
            ExtractionStatus.PENDING.getValue(),
            ExtractionStatus.PROCESSING.getValue());

    public ExtractionServiceImpl(ExtractionRepository extractionRepository,
                                  ExtractionFieldRepository extractionFieldRepository,
                                  ExtractionMapper extractionMapper,
                                  ExtractionFieldMapper extractionFieldMapper,
                                  ObjectMapper objectMapper,
                                  List<EntityExtractionAdapter> adapters) {
        this.extractionRepository = extractionRepository;
        this.extractionFieldRepository = extractionFieldRepository;
        this.extractionMapper = extractionMapper;
        this.extractionFieldMapper = extractionFieldMapper;
        this.objectMapper = objectMapper;
        Map<EntityType, EntityExtractionAdapter> map = new HashMap<>();
        for (EntityExtractionAdapter adapter : adapters) {
            map.put(adapter.entityType(), adapter);
        }
        this.adapterMap = Map.copyOf(map);
    }

    @Override
    @Transactional
    public ExtractionResult createExtraction(Long companyId, ExtractionCreateRequest request) {
        String companyIdStr = companyId.toString();
        String entityIdStr = request.getEntityId().toString();

        // Entity type is resolved server-side: ExtractionCreateRequest does not expose entityType.
        // Currently only TENDER is supported; the adapter registry enforces this.
        EntityExtractionAdapter adapter = resolveAdapter(EntityType.TENDER);
        adapter.verifyOwnership(companyIdStr, entityIdStr);

        List<String> documentIdStrings = adapter.resolveDocumentIds(entityIdStr, request);
        List<String> fieldNames = adapter.validateFieldNames(request.getFields());

        ExtractionsRecord inserted = buildAndInsertRecord(
                adapter.entityType(), companyIdStr, entityIdStr, documentIdStrings, fieldNames);

        Extraction dto = buildExtractionDto(inserted);
        return new ExtractionResult(dto, inserted.getVersion());
    }

    @Override
    public ExtractionListResponse listExtractions(Long companyId, UUID entityId,
                                                   ExtractionStatus status,
                                                   Integer limit, String cursor) {
        String companyIdStr = companyId.toString();
        String entityIdStr = entityId.toString();

        int effectiveLimit = clampLimit(limit);
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        ExtractionQuery query = ExtractionQuery.builder()
                .entityId(entityIdStr)
                .status(status != null ? status.getValue() : null)
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorPair != null ? cursorPair.createdAt() : null)
                .cursorId(cursorPair != null ? cursorPair.id() : null)
                .build();

        List<ExtractionsRecord> records = extractionRepository.findByEntityId(companyIdStr, entityIdStr, query);

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

        validateFieldsOwnedByExtraction(fieldIds, extractionId);

        applyFieldUpdates(request, fieldIds, extractionId, expectedVersion);

        List<ExtractionFieldsRecord> updatedFields = extractionFieldRepository.findAllByIds(fieldIds);
        List<ExtractionFieldsRecord> orderedFields = refetchFieldsInOrder(fieldIds, updatedFields);

        EntityType entityType = EntityType.fromValue(extraction.getEntityType());
        UUID entityId = UUID.fromString(extraction.getEntityId());
        List<ExtractionField> dtos = extractionFieldMapper.toDtoList(orderedFields, entityType, entityId);

        ExtractionFieldBulkUpdateResponse response = new ExtractionFieldBulkUpdateResponse();
        response.setData(dtos);
        return response;
    }

    // ---- Private helpers ----

    private EntityExtractionAdapter resolveAdapter(EntityType entityType) {
        EntityExtractionAdapter adapter = adapterMap.get(entityType);
        if (adapter == null) {
            throw new BadRequestException(
                    ApiErrorMessages.ENTITY_TYPE_NOT_SUPPORTED_CODE,
                    ApiErrorMessages.ENTITY_TYPE_NOT_SUPPORTED.formatted(entityType.getValue()));
        }
        return adapter;
    }

    private ExtractionsRecord findExtractionForCompany(String companyIdStr, String extractionId) {
        ExtractionsRecord record = extractionRepository.findById(extractionId);

        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(
                    ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                    ApiErrorMessages.EXTRACTION_NOT_FOUND);
        }

        return record;
    }

    private ExtractionsRecord buildAndInsertRecord(EntityType entityType,
                                                    String companyIdStr, String entityIdStr,
                                                    List<String> documentIdStrings,
                                                    List<String> fieldNames) {
        String extractionId = UUID.randomUUID().toString();
        JSONB documentIdsJsonb = serializeStringList(documentIdStrings);
        JSONB fieldNamesJsonb = fieldNames != null ? serializeStringList(fieldNames) : null;

        ExtractionsRecord record = new ExtractionsRecord();
        record.setId(extractionId);
        record.setCompanyId(companyIdStr);
        record.setEntityType(entityType.getValue());
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
            log.warn("Failed to parse extraction errors JSONB: {}", e.getMessage());
            return List.of();
        }
    }

    @SneakyThrows
    private JSONB serializeStringList(List<String> strings) {
        return JSONB.valueOf(objectMapper.writeValueAsString(strings));
    }

    @SneakyThrows
    private JSONB serializeObject(Object obj) {
        if (obj == null) return null;
        return JSONB.valueOf(objectMapper.writeValueAsString(obj));
    }

    private int clampLimit(Integer limit) {
        int effective = limit != null ? limit : 20;
        if (effective < 1 || effective > 100) {
            effective = 20;
        }
        return effective;
    }

    private void validateFieldsOwnedByExtraction(List<String> fieldIds, String extractionId) {
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
    }

    private void applyFieldUpdates(ExtractionFieldBulkUpdateRequest request,
                                    List<String> fieldIds,
                                    String extractionId,
                                    int expectedVersion) {
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
    }

    private List<ExtractionFieldsRecord> refetchFieldsInOrder(List<String> fieldIds,
                                                               List<ExtractionFieldsRecord> updatedFields) {
        return fieldIds.stream()
                .map(id -> updatedFields.stream()
                        .filter(f -> f.getId().equals(id))
                        .findFirst()
                        .orElseThrow())
                .toList();
    }
}
