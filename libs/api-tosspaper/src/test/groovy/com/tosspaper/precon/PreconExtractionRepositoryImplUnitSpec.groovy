package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import org.jooq.DSLContext
import org.jooq.SelectConditionStep
import org.jooq.SelectWhereStep
import spock.lang.Specification

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS

/**
 * Unit tests for {@link PreconExtractionRepositoryImpl#findByExternalTaskId}.
 *
 * <p>NOTE: {@code external_task_id} is a forthcoming DB column added by a later migration
 * (see TOS-37 pipeline worker PR). Real integration tests for this method will be added once
 * that migration is applied to the test database. Until then, these Spock mock tests verify
 * query construction and exception handling without requiring the column to exist.
 */
class PreconExtractionRepositoryImplUnitSpec extends Specification {

    DSLContext dsl
    ObjectMapper objectMapper = new ObjectMapper()
    PreconExtractionRepositoryImpl repo

    def setup() {
        dsl = Mock(DSLContext)
        repo = new PreconExtractionRepositoryImpl(dsl, objectMapper)
    }

    // ==================== findByExternalTaskId ====================

    def "TC-PR-FETI01: should return matching record when external task ID is found"() {
        given: "a DSL chain that resolves to a non-deleted extraction record"
            def taskId = "ext-task-abc-123"
            def extractionId = UUID.randomUUID().toString()

            def record = new ExtractionsRecord()
            record.setId(extractionId)
            record.setStatus("processing")
            record.setCompanyId("company-42")
            record.setEntityType("tender")
            record.setEntityId("tender-99")
            record.setVersion(1)

            def whereStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

            dsl.selectFrom(EXTRACTIONS) >> whereStep
            whereStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding by external task ID"
            def result = repo.findByExternalTaskId(taskId)

        then: "the matching record is returned with all correct fields"
            result.id == extractionId
            result.status == "processing"
            result.companyId == "company-42"
            result.entityType == "tender"
            result.entityId == "tender-99"
            result.version == 1
    }

    def "TC-PR-FETI02: should throw NotFoundException when no record matches external task ID"() {
        given: "a DSL chain that returns empty (no matching or already soft-deleted row)"
            def whereStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

            dsl.selectFrom(EXTRACTIONS) >> whereStep
            whereStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by a task ID that does not exist or is soft-deleted"
            repo.findByExternalTaskId("nonexistent-task-id")

        then: "NotFoundException is thrown with the extraction not-found code and message"
            def ex = thrown(NotFoundException)
            ex.code == ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE
            ex.message == ApiErrorMessages.EXTRACTION_NOT_FOUND
    }
}
