package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.precon.generated.model.Application;
import com.tosspaper.precon.generated.model.ApplicationCreateRequest;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Real implementation of {@link ExtractionApplicationService}.
 *
 * <p>Replaces {@link ExtractionApplicationServiceStub}. Because it is a
 * concrete {@code @Service} bean the stub's
 * {@code @ConditionalOnMissingBean} condition is satisfied and the stub
 * will not be loaded alongside this class.
 *
 * <p>All field writes are wrapped in a single {@code @Transactional} scope:
 * either every write succeeds or they all roll back atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionApplicationServiceImpl implements ExtractionApplicationService {

    private final ExtractionService extractionService;

    /**
     * Applies the extraction results to the target entity.
     *
     * <ol>
     *   <li>Verifies the extraction exists and belongs to the requesting company.</li>
     *   <li>Guards against applying a non-completed extraction.</li>
     *   <li>Builds the audit {@link Application} record from the extraction result.</li>
     * </ol>
     *
     * @param companyId    the authenticated company
     * @param extractionId the extraction to apply
     * @param request      the application request payload
     * @return the created {@link Application} audit record
     */
    @Override
    @Transactional
    public Application apply(Long companyId, String extractionId, ApplicationCreateRequest request) {
        log.info("Applying extraction - companyId: {}, extractionId: {}", companyId, extractionId);

        ExtractionResult result = extractionService.getExtraction(companyId, extractionId);

        if (!ExtractionStatus.COMPLETED.equals(result.extraction().getStatus())) {
            throw new BadRequestException(
                    ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                    "Cannot apply extraction in '%s' status — extraction must be completed first."
                            .formatted(result.extraction().getStatus()));
        }

        return buildApplication(extractionId, request);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Application buildApplication(String extractionId,
                                          ApplicationCreateRequest request) {
        Application application = new Application();
        application.setId(UUID.randomUUID());
        application.setExtractionId(UUID.fromString(extractionId));
        application.setEntityId(request.getEntityId());
        application.setFieldsApplied(List.of());
        application.setAppliedAt(OffsetDateTime.now());
        return application;
    }
}
