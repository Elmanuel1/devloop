package com.tosspaper.aiengine.repository.impl

import com.tosspaper.models.domain.ConformanceStatus
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionStatus
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.MatchType
import com.tosspaper.models.exception.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionTaskRecord
import org.jooq.*
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime
import java.util.UUID

import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK

/**
 * Unit tests for JooqExtractionTaskRepositoryImpl.
 * Tests all jOOQ-based extraction task repository operations with proper mocking.
 */
class JooqExtractionTaskRepositoryImplSpec extends Specification {

    DSLContext dsl = Mock()

    @Subject
    JooqExtractionTaskRepositoryImpl repository

    def setup() {
        repository = new JooqExtractionTaskRepositoryImpl(dsl)
    }

    // ==================== SAVE TESTS (UPSERT) ====================
    // NOTE: The save() method uses PostgreSQL's ON CONFLICT ... DO UPDATE upsert pattern.
    // jOOQ's upsert chain is extremely complex to mock (InsertSetStep -> InsertSetMoreStep ->
    // InsertOnConflictWhereIndexPredicateStep -> InsertOnDuplicateSetStep with multiple set() calls ->
    // InsertReturningStep -> fetchOne()). The chain involves dynamic type changes that are difficult
    // to correctly mock without brittle, implementation-specific tests.
    //
    // As per testing guidelines: "ON CONFLICT / upsert chains are extremely complex in jOOQ —
    // avoid testing upsert internals if the chain gets too deep. Focus on testing the business
    // logic and simpler queries."
    //
    // The save() upsert logic is thoroughly tested via integration tests with a real database.
    // Unit tests cover the simpler update(), find*(), and mapping methods.

    // ==================== UPDATE TESTS ====================

    def "update should update extraction task with expected status"() {
        given: "an extraction task to update"
            def task = createExtractionTask("task-123", 1L)
            task.status = ExtractionStatus.COMPLETED
            task.taskId = "provider-task-123"
            task.conformanceScore = 0.95

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            dsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating task"
            def result = repository.update(task, ExtractionStatus.STARTED)

        then: "task is returned"
            result != null
            result.assignedId == "task-123"
    }

    def "update should throw RuntimeException when status mismatch"() {
        given: "an extraction task to update"
            def task = createExtractionTask("task-123", 1L)
            task.status = ExtractionStatus.COMPLETED

        and: "update chain returning 0 rows"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            dsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 0

        when: "updating task with wrong expected status"
            repository.update(task, ExtractionStatus.PENDING)

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Extraction task not found or status mismatch")
            ex.message.contains("task-123")
    }

    def "update with DSLContext should update extraction task"() {
        given: "a transaction context"
            def txDsl = Mock(DSLContext)

        and: "an extraction task to update"
            def task = createExtractionTask("task-123", 1L)
            task.status = ExtractionStatus.COMPLETED
            task.documentType = DocumentType.INVOICE
            task.conformanceStatus = ConformanceStatus.VALIDATED

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            txDsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating task with transaction context"
            def result = repository.update(txDsl, task, ExtractionStatus.STARTED)

        then: "task is returned"
            result != null
            result.assignedId == "task-123"
    }

    def "update should handle null optional fields"() {
        given: "an extraction task with null optional fields"
            def task = createExtractionTask("task-123", 1L)
            task.status = ExtractionStatus.PENDING
            task.documentType = null
            task.conformanceScore = null
            task.conformanceStatus = null

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            dsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating task"
            def result = repository.update(task, ExtractionStatus.PENDING)

        then: "task is returned"
            result != null
            result.assignedId == "task-123"
    }

    // ==================== FIND BY TASK ID TESTS ====================

    def "findByTaskId should return extraction task when found"() {
        given: "a task ID"
            def taskId = "provider-task-123"

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def record = createRecord("task-123", 1L)
            record.setTaskId(taskId)

        and: "mock jOOQ select chain"
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding by task ID"
            def result = repository.findByTaskId(taskId)

        then: "task is returned"
            result.isPresent()
            result.get().taskId == taskId
            result.get().assignedId == "task-123"
    }

    def "findByTaskId should return empty when not found"() {
        given: "a non-existent task ID"
            def taskId = "nonexistent"

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ select chain"
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by task ID"
            def result = repository.findByTaskId(taskId)

        then: "empty is returned"
            result.isEmpty()
    }

    // ==================== FIND BY ASSIGNED ID TESTS ====================

    def "findByAssignedId should return extraction task when found"() {
        given: "an assigned ID"
            def assignedId = "task-123"

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def record = createRecord(assignedId, 1L)

        and: "mock jOOQ select chain"
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding by assigned ID"
            def result = repository.findByAssignedId(assignedId)

        then: "task is returned"
            result != null
            result.assignedId == assignedId
    }

    def "findByAssignedId should throw NotFoundException when not found"() {
        given: "a non-existent assigned ID"
            def assignedId = "nonexistent"

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ select chain"
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by assigned ID"
            repository.findByAssignedId(assignedId)

        then: "exception is thrown"
            def ex = thrown(NotFoundException)
            ex.message.contains("ExtractionTask not found")
            ex.message.contains(assignedId)
    }

    // ==================== UPDATE MANUAL PO INFORMATION TESTS ====================

    def "updateManualPoInformation should update PO fields"() {
        given: "an extraction task with manual PO info"
            def task = createExtractionTask("task-123", 1L)
            task.poNumber = "PO-12345"
            task.projectId = "proj-456"
            task.purchaseOrderId = "po-id-789"
            task.matchType = MatchType.MANUAL

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            dsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating manual PO information"
            repository.updateManualPoInformation(task)

        then: "update is executed"
            noExceptionThrown()
    }

    def "updateManualPoInformation should handle null optional fields"() {
        given: "an extraction task with minimal PO info"
            def task = createExtractionTask("task-123", 1L)
            task.poNumber = null
            task.projectId = null
            task.purchaseOrderId = null
            task.matchType = MatchType.MANUAL

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock jOOQ update chain"
            dsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating manual PO information"
            repository.updateManualPoInformation(task)

        then: "update is executed"
            noExceptionThrown()
    }

    // ==================== RECORD MAPPING TESTS ====================

    def "mapRecordToDomain should map all fields correctly"() {
        given: "a complete record"
            def record = createCompleteRecord()

        and: "select chain to trigger mapping"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding task (triggers mapping)"
            def result = repository.findByAssignedId("task-123")

        then: "all fields are mapped correctly"
            result.assignedId == "task-123"
            result.companyId == 1L
            result.storageKey == "s3://bucket/key"
            result.status == ExtractionStatus.COMPLETED
            result.taskId == "provider-task-123"
            result.documentType == DocumentType.INVOICE
            result.conformanceStatus == ConformanceStatus.VALIDATED
            result.matchType == MatchType.AI_MATCH
            result.fromAddress == "sender@example.com"
            result.toAddress == "receiver@example.com"
            result.emailSubject == "Invoice #123"
    }

    def "mapRecordToDomain should handle null JSONB fields"() {
        given: "a record with null JSONB fields"
            def record = createRecord("task-123", 1L)
            record.setExtractTaskResults(null)
            record.setConformedJson(null)
            record.setConformanceHistory(null)
            record.setConformanceEvaluation(null)
            record.setMatchReport(null)

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding task"
            def result = repository.findByAssignedId("task-123")

        then: "null JSONB fields are handled"
            result.extractTaskResults == null
            result.conformedJson == null
            result.conformanceHistory == null
            result.conformanceEvaluation == null
            result.matchReport == null
    }

    def "mapRecordToDomain should handle null optional enum fields"() {
        given: "a record with null optional fields"
            def record = createRecord("task-123", 1L)
            record.setDocumentType(null)
            record.setConformanceStatus(null)

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            dsl.selectFrom(EXTRACTION_TASK) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding task"
            def result = repository.findByAssignedId("task-123")

        then: "null enums are handled"
            result.documentType == null
            result.conformanceStatus == null
    }

    // ==================== HELPER METHODS ====================

    private ExtractionTask createExtractionTask(String assignedId, Long companyId) {
        ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(companyId)
            .storageKey("s3://bucket/key")
            .status(ExtractionStatus.PENDING)
            .fromAddress("sender@example.com")
            .toAddress("receiver@example.com")
            .emailSubject("Test Subject")
            .emailMessageId(UUID.randomUUID())
            .emailThreadId(UUID.randomUUID())
            .receivedAt(OffsetDateTime.now())
            .createdAt(OffsetDateTime.now())
            .matchType(MatchType.PENDING)
            .build()
    }

    private ExtractionTaskRecord createRecord(String assignedId, Long companyId) {
        def record = new ExtractionTaskRecord()
        record.setAssignedId(assignedId)
        record.setCompanyId(companyId)
        record.setStorageKey("s3://bucket/key")
        record.setStatus(ExtractionStatus.PENDING.displayName)
        record.setAttempts(1)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        record.setFromAddress("sender@example.com")
        record.setToAddress("receiver@example.com")
        record.setEmailSubject("Test Subject")
        record.setMatchType(MatchType.PENDING.value)
        return record
    }

    private ExtractionTaskRecord createCompleteRecord() {
        def record = createRecord("task-123", 1L)
        record.setStatus(ExtractionStatus.COMPLETED.displayName)
        record.setTaskId("provider-task-123")
        record.setPreparationId("prep-123")
        record.setDocumentType(DocumentType.INVOICE.filePrefix)
        record.setConformanceScore(new BigDecimal("0.95"))
        record.setConformanceStatus(ConformanceStatus.VALIDATED.name())
        record.setConformanceAttempts(1)
        record.setMatchType(MatchType.AI_MATCH.value)
        record.setPoNumber("PO-12345")
        record.setProjectId("proj-456")
        record.setPurchaseOrderId("po-id-789")
        record.setEmailSubject("Invoice #123")
        record.setExtractTaskResults(org.jooq.JSONB.jsonb('{"key":"value"}'))
        record.setConformedJson(org.jooq.JSONB.jsonb('{"invoice":"data"}'))
        record.setConformanceHistory(org.jooq.JSONB.jsonb('[]'))
        record.setConformanceEvaluation(org.jooq.JSONB.jsonb('{"score":0.95}'))
        record.setMatchReport(org.jooq.JSONB.jsonb('{"matched":true}'))
        return record
    }
}
