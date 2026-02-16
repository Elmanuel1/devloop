package com.tosspaper.aiengine.repository.impl

import com.tosspaper.models.domain.DocumentApproval
import com.tosspaper.models.domain.DocumentSyncStatus
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.exception.NotFoundException
import com.tosspaper.models.jooq.tables.records.DocumentApprovalsRecord
import com.tosspaper.models.query.DocumentApprovalQuery
import org.jooq.*
import org.jooq.impl.DSL
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.DOCUMENT_APPROVALS
import static com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS

/**
 * Unit tests for JooqDocumentApprovalRepositoryImpl.
 * Tests jOOQ-based document approval repository operations.
 */
class JooqDocumentApprovalRepositoryImplSpec extends Specification {

    DSLContext dsl = Mock()

    @Subject
    JooqDocumentApprovalRepositoryImpl repository

    def setup() {
        repository = new JooqDocumentApprovalRepositoryImpl(dsl)
    }

    // ==================== FIND BY ID TESTS ====================

    def "findById should return document approval when found"() {
        given: "record exists"
            def record = createRecord("appr-123", "attach-123", 1L)
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding by ID"
            def result = repository.findById("appr-123")

        then: "approval is returned"
            result.id == "appr-123"
            result.assignedId == "attach-123"
            result.companyId == 1L
    }

    def "findById should throw NotFoundException when not found"() {
        given: "record does not exist"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by ID"
            repository.findById("missing")

        then: "exception is thrown"
            thrown(NotFoundException)
    }

    // ==================== FIND BY ASSIGNED ID TESTS ====================

    def "findByAssignedId should return document approval when found"() {
        given: "record exists"
            def record = createRecord("appr-123", "attach-123", 1L)
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)

        when: "finding by assigned ID"
            def result = repository.findByAssignedId("attach-123")

        then: "approval is returned"
            result.isPresent()
            result.get().assignedId == "attach-123"
    }

    def "findByAssignedId should return empty when not found"() {
        given: "record does not exist"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by assigned ID"
            def result = repository.findByAssignedId("missing")

        then: "empty is returned"
            result.isEmpty()
    }

    // ==================== APPROVE TESTS ====================

    def "approve should update approval record"() {
        given: "transaction context - approve takes DSLContext, not Configuration"
            def txDsl = Mock(DSLContext)

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)
            def returningStep = Mock(UpdateResultStep)
            def record = createRecord("appr-123", "attach-123", 1L)

        and: "mock jOOQ update chain - approve calls ctx.dsl().update()"
            txDsl.dsl() >> txDsl
            txDsl.update(DOCUMENT_APPROVALS) >> updateStep
            updateStep.set(_, _ as OffsetDateTime) >> updateMoreStep
            updateMoreStep.set(_, _ as String) >> updateMoreStep
            updateMoreStep.where(_) >> conditionStep
            conditionStep.returning() >> returningStep
            returningStep.fetchSingle() >> record

        when: "approving"
            def result = repository.approve(txDsl, "appr-123", "proj-456", "user-123", "Looks good")

        then: "approval is updated"
            result.id == "appr-123"
    }

    // ==================== REJECT TESTS ====================

    def "reject should update approval record with rejection"() {
        given: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)
            def returningStep = Mock(UpdateResultStep)
            def record = createRecord("appr-123", "attach-123", 1L)

        and: "transaction result - reject calls dsl.transactionResult(ctx -> ctx.dsl().update(...))"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)
            txContext.dsl() >> txDsl
            txDsl.update(DOCUMENT_APPROVALS) >> updateStep
            updateStep.set(_, _ as OffsetDateTime) >> updateMoreStep
            updateMoreStep.set(_, _ as String) >> updateMoreStep
            updateMoreStep.where(_) >> conditionStep
            conditionStep.returning() >> returningStep
            returningStep.fetchSingle() >> record

        and: "mock transaction"
            dsl.transactionResult(_) >> { args ->
                def lambda = args[0] as TransactionalCallable
                lambda.run(txContext)
            }

        when: "rejecting"
            repository.reject("appr-123", "user-123", "Not valid")

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== FIND BY QUERY TESTS ====================

    def "findByQuery should return approvals matching query"() {
        given: "query with company filter"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def records = [
                createRecord("appr-1", "attach-1", 1L),
                createRecord("appr-2", "attach-2", 1L)
            ]

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> records.collect { mapRecordToDomain(it) }

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "approvals are returned"
            results.size() == 2
            results[0].companyId == 1L
            results[1].companyId == 1L
    }

    def "findByQuery should filter by status pending"() {
        given: "query with pending status"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .status("pending")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def records = [createRecord("appr-1", "attach-1", 1L)]

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> records.collect { mapRecordToDomain(it) }

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "pending approvals are returned"
            results.size() == 1
    }

    def "findByQuery should filter by status approved"() {
        given: "query with approved status"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .status("approved")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)
            record.setApprovedAt(OffsetDateTime.now())

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "approved approvals are returned"
            results.size() == 1
            results[0].approvedAt != null
    }

    def "findByQuery should filter by status rejected"() {
        given: "query with rejected status"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .status("rejected")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)
            record.setRejectedAt(OffsetDateTime.now())

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "rejected approvals are returned"
            results.size() == 1
            results[0].rejectedAt != null
    }

    def "findByQuery should filter by project ID"() {
        given: "query with project filter"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .projectId("proj-456")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)
            record.setProjectId("proj-456")

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "project-filtered approvals are returned"
            results.size() == 1
            results[0].projectId == "proj-456"
    }

    def "findByQuery should filter by document type"() {
        given: "query with document type filter"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .documentType("INVOICE")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)
            record.setDocumentType("INVOICE")

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "type-filtered approvals are returned"
            results.size() == 1
            results[0].documentType == "INVOICE"
    }

    def "findByQuery should filter by date range"() {
        given: "query with date range"
            def from = OffsetDateTime.now().minusDays(7)
            def to = OffsetDateTime.now()
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .createdDateFrom(from)
                .createdDateTo(to)
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "date-filtered approvals are returned"
            results.size() == 1
    }

    def "findByQuery should support cursor pagination"() {
        given: "query with cursor"
            def cursorAt = OffsetDateTime.now().minusDays(1)
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .cursorCreatedAt(cursorAt)
                .cursorId("appr-100")
                .pageSize(10)
                .build()

        and: "select chain"
            def selectStep = Mock(SelectWhereStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)

        and: "mock jOOQ select chain"
            dsl.selectFrom(DOCUMENT_APPROVALS) >> selectStep
            selectStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> []

        when: "finding by query"
            def results = repository.findByQuery(query)

        then: "query executes with cursor"
            results.isEmpty()
    }

    // ==================== CREATE INITIAL APPROVAL TESTS ====================

    def "createInitialApproval should insert new approval"() {
        given: "transaction context"
            def txContext = Mock(DSLContext)

        and: "insert chain"
            def insertStep = Mock(InsertSetStep)
            def insertMoreStep = Mock(InsertSetMoreStep)
            def onConflictStep = Mock(InsertOnConflictWhereIndexPredicateStep)
            def doNothingStep = Mock(InsertOnConflictWhereStep)

        and: "mock jOOQ insert chain"
            txContext.insertInto(DOCUMENT_APPROVALS) >> insertStep
            insertStep.set(_, _) >> insertMoreStep
            insertMoreStep.set(_, _) >> insertMoreStep
            insertMoreStep.onConflict(_) >> onConflictStep
            onConflictStep.doNothing() >> doNothingStep
            doNothingStep.execute() >> 1

        when: "creating initial approval"
            repository.createInitialApproval(
                txContext,
                "PO-123",
                "INV-001",
                "attach-123",
                1L,
                "sender@example.com",
                DocumentType.INVOICE
            )

        then: "insert is executed"
            noExceptionThrown()
    }

    // ==================== CREATE AUTO-APPROVED RECORD TESTS ====================

    def "createAutoApprovedRecord should insert auto-approved approval"() {
        given: "transaction context"
            def txContext = Mock(DSLContext)

        and: "insert chain"
            def insertStep = Mock(InsertSetStep)
            def insertMoreStep = Mock(InsertSetMoreStep)
            def onConflictStep = Mock(InsertOnConflictWhereIndexPredicateStep)
            def doNothingStep = Mock(InsertOnConflictWhereStep)
            def returningStep = Mock(InsertResultStep)
            def record = createRecord("appr-123", "attach-123", 1L)
            record.setApprovedAt(OffsetDateTime.now())

        and: "mock jOOQ insert chain"
            txContext.insertInto(DOCUMENT_APPROVALS) >> insertStep
            insertStep.set(_, _) >> insertMoreStep
            insertMoreStep.set(_, _) >> insertMoreStep
            insertMoreStep.onConflict(_) >> onConflictStep
            onConflictStep.doNothing() >> doNothingStep
            doNothingStep.returning() >> returningStep
            returningStep.fetchSingle() >> record

        when: "creating auto-approved record"
            repository.createAutoApprovedRecord(
                txContext,
                "PO-123",
                "INV-001",
                "attach-123",
                1L,
                "sender@example.com",
                DocumentType.INVOICE,
                "proj-456"
            )

        then: "insert is executed"
            noExceptionThrown()
    }

    // ==================== FIND APPROVED FOR SYNC TESTS ====================

    def "findApprovedForSync should return approved documents for sync"() {
        given: "connection ID and cursor"
            def connectionId = "conn-123"
            def cursorAt = OffsetDateTime.now().minusDays(1)
            def cursorId = "appr-100"

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)
            def record = createRecord("appr-1", "attach-1", 1L)
            record.setApprovedAt(OffsetDateTime.now())

        and: "mock jOOQ select chain"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_APPROVALS) >> fromStep
            fromStep.join(INTEGRATION_CONNECTIONS) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> [mapRecordToDomain(record)]

        when: "finding approved for sync"
            def results = repository.findApprovedForSync(connectionId, cursorAt, cursorId, 10)

        then: "approved documents are returned"
            results.size() == 1
            results[0].approvedAt != null
    }

    def "findApprovedForSync should work without cursor"() {
        given: "connection ID without cursor"
            def connectionId = "conn-123"

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)
            def orderStep = Mock(SelectSeekStep2)
            def limitStep = Mock(SelectLimitPercentStep)

        and: "mock jOOQ select chain"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_APPROVALS) >> fromStep
            fromStep.join(INTEGRATION_CONNECTIONS) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_ as Condition) >> conditionStep
            conditionStep.orderBy(_, _) >> orderStep
            orderStep.limit(10) >> limitStep
            limitStep.fetch(_) >> []

        when: "finding approved for sync"
            def results = repository.findApprovedForSync(connectionId, null, null, 10)

        then: "query executes successfully"
            results.isEmpty()
    }

    // ==================== HELPER METHODS ====================

    private DocumentApprovalsRecord createRecord(String id, String assignedId, Long companyId) {
        def record = new DocumentApprovalsRecord()
        record.setId(id)
        record.setAssignedId(assignedId)
        record.setCompanyId(companyId)
        record.setFromEmail("sender@example.com")
        record.setDocumentType("INVOICE")
        record.setCreatedAt(OffsetDateTime.now())
        return record
    }

    private DocumentApproval mapRecordToDomain(DocumentApprovalsRecord record) {
        DocumentApproval.builder()
            .id(record.getId())
            .assignedId(record.getAssignedId())
            .companyId(record.getCompanyId())
            .fromEmail(record.getFromEmail())
            .documentType(record.getDocumentType())
            .projectId(record.getProjectId())
            .approvedAt(record.getApprovedAt())
            .rejectedAt(record.getRejectedAt())
            .reviewedBy(record.getReviewedBy())
            .reviewNotes(record.getReviewNotes())
            .documentSummary(record.getDocumentSummary())
            .storageKey(record.getStorageKey())
            .createdAt(record.getCreatedAt())
            .externalDocumentNumber(record.getExternalDocumentNumber())
            .poNumber(record.getPoNumber())
            .syncStatus(record.getSyncStatus() != null
                ? DocumentSyncStatus.fromValue(record.getSyncStatus())
                : null)
            .lastSyncAttempt(record.getLastSyncAttempt())
            .build()
    }
}
