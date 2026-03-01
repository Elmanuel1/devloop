package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

/**
 * jOOQ-based implementation of {@link PreconExtractionRepository}.
 *
 * <p>{@code external_task_id} is a forthcoming column (added by a later
 * migration). Until the jOOQ-generated schema catches up it is accessed via
 * {@link DSL#field(String, Class)}, the same pattern used for the V3.5
 * columns ({@code started_at}, {@code completed_at}, {@code errors}).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PreconExtractionRepositoryImpl implements PreconExtractionRepository {

    private static final org.jooq.Field<String> EXTERNAL_TASK_ID =
            DSL.field("external_task_id", String.class);

    private final DSLContext dsl;

    @Override
    public ExtractionsRecord findByExternalTaskId(String externalTaskId) {
        return dsl.selectFrom(EXTRACTIONS)
                .where(EXTERNAL_TASK_ID.eq(externalTaskId))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(
                        ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_NOT_FOUND));
    }

    @Override
    public List<ExtractionsRecord> findPendingExtractions() {
        return dsl.selectFrom(EXTRACTIONS)
                .where(EXTRACTIONS.STATUS.eq(ExtractionStatus.PENDING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .fetch();
    }

}
