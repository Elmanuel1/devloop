package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.ExtractionCreateRequest;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import com.tosspaper.precon.generated.model.TenderFieldName;
import com.tosspaper.precon.generated.model.TenderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenderExtractionAdapter implements EntityExtractionAdapter {

    private final TenderRepository tenderRepository;
    private final TenderDocumentRepository tenderDocumentRepository;

    private static final Set<String> VALID_TENDER_FIELD_NAMES = Arrays.stream(TenderFieldName.values())
            .map(TenderFieldName::getValue)
            .collect(Collectors.toSet());

    private static final Set<String> INACTIVE_TENDER_STATUSES = Set.of(
            TenderStatus.WON.getValue(),
            TenderStatus.LOST.getValue(),
            TenderStatus.CANCELLED.getValue());

    @Override
    public EntityType entityType() {
        return EntityType.TENDER;
    }

    @Override
    public boolean verifyOwnership(String companyId, String entityId) {
        TendersRecord tender = tenderRepository.findById(entityId);

        if (INACTIVE_TENDER_STATUSES.contains(tender.getStatus())) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_TENDER_NOT_ACTIVE_CODE,
                    ApiErrorMessages.EXTRACTION_TENDER_NOT_ACTIVE.formatted(tender.getStatus()));
        }

        return tender.getCompanyId().equals(companyId);
    }

    @Override
    public List<String> resolveDocumentIds(String entityId, ExtractionCreateRequest request) {
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            List<String> requestedIds = request.getDocumentIds().stream()
                    .map(UUID::toString)
                    .toList();

            List<TenderDocumentsRecord> docs = tenderDocumentRepository.findByIds(requestedIds);

            if (docs.size() != requestedIds.size()) {
                throw new NotFoundException(
                        ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE,
                        ApiErrorMessages.DOCUMENT_NOT_FOUND);
            }

            docs.stream()
                    .filter(d -> !d.getTenderId().equals(entityId))
                    .findFirst()
                    .ifPresent(d -> {
                        throw new BadRequestException(
                                ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED_CODE,
                                ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED.formatted(d.getId(), entityId));
                    });

            docs.stream()
                    .filter(d -> !TenderDocumentStatus.READY.getValue().equals(d.getStatus()))
                    .findFirst()
                    .ifPresent(d -> {
                        throw new BadRequestException(
                                ApiErrorMessages.EXTRACTION_DOC_NOT_READY_CODE,
                                ApiErrorMessages.EXTRACTION_DOC_NOT_READY.formatted(d.getId(), d.getStatus()));
                    });

            return requestedIds;
        }

        List<TenderDocumentsRecord> readyDocs = tenderDocumentRepository.findByTenderId(
                entityId, TenderDocumentStatus.READY.getValue(), 200, null, null);
        if (readyDocs.isEmpty()) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE,
                    ApiErrorMessages.EXTRACTION_NO_READY_DOCS.formatted(entityId));
        }
        return readyDocs.stream()
                .map(TenderDocumentsRecord::getId)
                .toList();
    }

    @Override
    public List<String> validateFieldNames(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        for (String fieldName : fields) {
            if (!VALID_TENDER_FIELD_NAMES.contains(fieldName)) {
                throw new BadRequestException(
                        ApiErrorMessages.EXTRACTION_INVALID_FIELD_CODE,
                        ApiErrorMessages.EXTRACTION_INVALID_FIELD.formatted(fieldName, EntityType.TENDER.getValue()));
            }
        }
        return fields;
    }
}
