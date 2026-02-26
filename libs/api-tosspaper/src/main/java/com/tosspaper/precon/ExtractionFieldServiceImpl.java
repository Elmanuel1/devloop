package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.exception.IfMatchRequiredException;
import com.tosspaper.models.exception.StaleVersionException;
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.ExtractionField;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateRequest;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateResponse;
import com.tosspaper.precon.generated.model.ExtractionFieldListResponse;
import com.tosspaper.precon.generated.model.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionFieldServiceImpl implements ExtractionFieldService {

    private final ExtractionRepository extractionRepository;
    private final ExtractionFieldRepository extractionFieldRepository;
    private final ExtractionFieldMapper extractionFieldMapper;
    private final ExtractionJsonConverter jsonConverter;

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

        applyFieldUpdates(request, extractionId, expectedVersion);

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

    private ExtractionsRecord findExtractionForCompany(String companyIdStr, String extractionId) {
        ExtractionsRecord record = extractionRepository.findById(extractionId);

        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(
                    ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                    ApiErrorMessages.EXTRACTION_NOT_FOUND);
        }

        return record;
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
                                    String extractionId,
                                    int expectedVersion) {
        request.getUpdates().forEach(item -> {
            String fieldId = item.getFieldId().toString();
            try {
                JSONB editedValueJsonb = jsonConverter.objectToJsonb(item.getEditedValue());
                extractionFieldRepository.updateEditedValue(fieldId, editedValueJsonb);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize edited value for field " + fieldId, e);
            }
        });

        int rowsUpdated = extractionRepository.updateVersion(extractionId, expectedVersion);
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

    private int clampLimit(Integer limit) {
        int effective = limit != null ? limit : 20;
        if (effective < 1 || effective > 100) {
            effective = 20;
        }
        return effective;
    }
}
