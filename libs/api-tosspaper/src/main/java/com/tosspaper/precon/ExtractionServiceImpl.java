package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.common.PaginationUtils;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.Extraction;
import com.tosspaper.precon.generated.model.ExtractionCreateRequest;
import com.tosspaper.precon.generated.model.ExtractionError;
import com.tosspaper.precon.generated.model.ExtractionListResponse;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import com.tosspaper.precon.generated.model.Pagination;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class ExtractionServiceImpl implements ExtractionService {

    private final ExtractionRepository extractionRepository;
    private final ExtractionFieldRepository extractionFieldRepository;
    private final ExtractionMapper extractionMapper;
    private final ExtractionJsonConverter jsonConverter;
    private final Map<EntityType, EntityExtractionAdapter> adapterMap;

    // Valid statuses for cancellation (any non-final, non-cancelled)
    private static final Set<String> CANCELLABLE_STATUSES = Set.of(
            ExtractionStatus.PENDING.getValue(),
            ExtractionStatus.PROCESSING.getValue());

    public ExtractionServiceImpl(ExtractionRepository extractionRepository,
                                  ExtractionFieldRepository extractionFieldRepository,
                                  ExtractionMapper extractionMapper,
                                  ExtractionJsonConverter jsonConverter,
                                  List<EntityExtractionAdapter> adapters) {
        this.extractionRepository = extractionRepository;
        this.extractionFieldRepository = extractionFieldRepository;
        this.extractionMapper = extractionMapper;
        this.jsonConverter = jsonConverter;
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

        EntityExtractionAdapter adapter = resolveAdapter(request.getEntityType());
        if (!adapter.verifyOwnership(companyIdStr, entityIdStr)) {
            throw new NotFoundException(
                    ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                    ApiErrorMessages.EXTRACTION_NOT_FOUND);
        }

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

        int effectiveLimit = PaginationUtils.clampLimit(limit);
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        ExtractionQuery query = ExtractionQuery.builder()
                .entityId(entityIdStr)
                .status(status != null ? status.getValue() : null)
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorPair != null ? cursorPair.createdAt() : null)
                .cursorId(cursorPair != null ? cursorPair.id() : null)
                .build();

        List<ExtractionsRecord> records = extractionRepository.findByEntityId(companyIdStr, entityIdStr, query);

        boolean hasMore = PaginationUtils.hasMore(records, effectiveLimit);
        records = PaginationUtils.truncate(records, effectiveLimit);

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

        if (!CANCELLABLE_STATUSES.contains(record.getStatus())) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_CANNOT_CANCEL_CODE,
                    ApiErrorMessages.EXTRACTION_CANNOT_CANCEL.formatted(record.getStatus()));
        }

        extractionRepository.updateStatus(extractionId, ExtractionStatus.CANCELLED.getValue());
        extractionFieldRepository.deleteByExtractionId(extractionId);
    }

    // ---- Private helpers ----

    private EntityExtractionAdapter resolveAdapter(EntityType entityType) {
        EntityExtractionAdapter adapter = adapterMap.get(entityType);
        if (adapter == null) {
            throw new BadRequestException(
                    ApiErrorMessages.ENTITY_TYPE_NOT_SUPPORTED_CODE,
                    ApiErrorMessages.ENTITY_TYPE_NOT_SUPPORTED.formatted(
                            entityType != null ? entityType.getValue() : "null"));
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
        JSONB documentIdsJsonb = jsonConverter.stringListToJsonb(documentIdStrings);
        JSONB fieldNamesJsonb = fieldNames.isEmpty() ? null : jsonConverter.stringListToJsonb(fieldNames);

        ExtractionInsertParams params = new ExtractionInsertParams(
                extractionId, companyIdStr, entityType, entityIdStr,
                documentIdsJsonb, fieldNamesJsonb);
        ExtractionsRecord record = extractionMapper.toRecord(params);
        return extractionRepository.insert(record);
    }

    /**
     * Build an Extraction DTO including the three V3.5 columns (started_at, completed_at, errors).
     * These columns are not in the jOOQ-generated ExtractionsRecord schema; they are accessed
     * via field-name lookup which safely returns null when not present in the result set.
     */
    private Extraction buildExtractionDto(ExtractionsRecord record) {
        OffsetDateTime startedAt = safeGet(record, "started_at", OffsetDateTime.class);
        OffsetDateTime completedAt = safeGet(record, "completed_at", OffsetDateTime.class);
        JSONB errorsJsonb = safeGet(record, "errors", JSONB.class);

        List<ExtractionError> errors = jsonConverter.jsonbToErrorList(errorsJsonb);

        Extraction dto = extractionMapper.toDto(record);
        dto.setStartedAt(startedAt);
        dto.setCompletedAt(completedAt);
        dto.setErrors(errors);
        return dto;
    }

    /**
     * Safely retrieves a field value from a record by name, returning null if the field
     * is not present in the record's field set (e.g., columns added after jOOQ code-gen).
     */
    @SuppressWarnings("unchecked")
    private <T> T safeGet(ExtractionsRecord record, String fieldName, Class<T> type) {
        for (org.jooq.Field<?> f : record.fields()) {
            if (f.getName().equals(fieldName)) {
                return (T) record.get(f);
            }
        }
        return null;
    }
}
