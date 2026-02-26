package com.tosspaper.precon

import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.common.BadRequestException
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.jooq.tables.records.TendersRecord
import com.tosspaper.precon.generated.model.EntityType
import com.tosspaper.precon.generated.model.ExtractionCreateRequest
import com.tosspaper.precon.generated.model.TenderDocumentStatus
import com.tosspaper.precon.generated.model.TenderFieldName
import spock.lang.Specification

import java.time.OffsetDateTime

class TenderExtractionAdapterSpec extends Specification {

    TenderRepository tenderRepository
    TenderDocumentRepository tenderDocumentRepository
    TenderExtractionAdapter adapter

    def TENDER_ID     = "tender-abc-0001"
    def COMPANY_ID    = "42"
    def OTHER_COMPANY = "999"

    def setup() {
        tenderRepository = Mock()
        tenderDocumentRepository = Mock()
        adapter = new TenderExtractionAdapter(tenderRepository, tenderDocumentRepository)
    }

    // ==================== entityType ====================

    def "TC-A-ET01: should return EntityType.TENDER"() {
        when: "asking for entity type"
            def result = adapter.entityType()

        then: "TENDER is returned"
            result == EntityType.TENDER
    }

    // ==================== verifyOwnership ====================

    def "TC-A-V01: should return true when tender belongs to company with status pending"() {
        given: "a pending tender owned by the company"
            def tender = buildTendersRecord(TENDER_ID, COMPANY_ID, "pending")
            tenderRepository.findById(TENDER_ID) >> tender

        when: "verifying ownership"
            def result = adapter.verifyOwnership(COMPANY_ID, TENDER_ID)

        then: "returns true — tender is owned and active"
            result == true
    }

    def "TC-A-V02: should return true when tender belongs to company with status submitted"() {
        given: "a submitted tender owned by the company"
            def tender = buildTendersRecord(TENDER_ID, COMPANY_ID, "submitted")
            tenderRepository.findById(TENDER_ID) >> tender

        when: "verifying ownership"
            def result = adapter.verifyOwnership(COMPANY_ID, TENDER_ID)

        then: "returns true — submitted tender is still active for extraction"
            result == true
    }

    def "TC-A-V03: should propagate NotFoundException when tender does not exist"() {
        given: "repository throws NotFoundException for unknown tender"
            tenderRepository.findById(TENDER_ID) >> {
                throw new NotFoundException(ApiErrorMessages.TENDER_NOT_FOUND_CODE, ApiErrorMessages.TENDER_NOT_FOUND)
            }

        when: "verifying ownership"
            adapter.verifyOwnership(COMPANY_ID, TENDER_ID)

        then: "NotFoundException propagates to the caller"
            def ex = thrown(NotFoundException)
            ex.code == ApiErrorMessages.TENDER_NOT_FOUND_CODE
    }

    def "TC-A-V04: should return false when tender belongs to a different company"() {
        given: "a tender owned by a different company"
            def tender = buildTendersRecord(TENDER_ID, OTHER_COMPANY, "pending")
            tenderRepository.findById(TENDER_ID) >> tender

        when: "verifying ownership against our company"
            def result = adapter.verifyOwnership(COMPANY_ID, TENDER_ID)

        then: "returns false — ownership check fails"
            result == false
    }

    def "TC-A-V05: should throw BadRequestException when tender is in an inactive status"() {
        given: "a tender in a terminal/inactive status"
            def tender = buildTendersRecord(TENDER_ID, COMPANY_ID, inactiveStatus)
            tenderRepository.findById(TENDER_ID) >> tender

        when: "verifying ownership"
            adapter.verifyOwnership(COMPANY_ID, TENDER_ID)

        then: "BadRequestException thrown with tenderNotActive code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_TENDER_NOT_ACTIVE_CODE
            ex.message.contains(inactiveStatus)

        where:
            inactiveStatus << ["won", "lost", "cancelled"]
    }

    // ==================== resolveDocumentIds — explicit ====================

    def "TC-A-R01: should return list of ID strings when all explicit documents are valid and owned"() {
        given: "a request with two valid document IDs"
            def docId1 = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def docId2 = UUID.fromString("bbbb0002-0000-0000-0000-000000000002")
            def request = buildRequest([docId1, docId2])

            def doc1 = buildDocumentRecord(docId1.toString(), TENDER_ID, "ready")
            def doc2 = buildDocumentRecord(docId2.toString(), TENDER_ID, "ready")
            tenderDocumentRepository.findByIds([docId1.toString(), docId2.toString()]) >> [doc1, doc2]

        when: "resolving document IDs"
            def result = adapter.resolveDocumentIds(TENDER_ID, request)

        then: "both document IDs are returned as strings"
            result == [docId1.toString(), docId2.toString()]
    }

    def "TC-A-R02: should throw NotFoundException when one requested document is not found"() {
        given: "a request with two document IDs but only one exists in the DB"
            def docId1 = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def docId2 = UUID.fromString("bbbb0002-0000-0000-0000-000000000002")
            def request = buildRequest([docId1, docId2])

            def doc1 = buildDocumentRecord(docId1.toString(), TENDER_ID, "ready")
            // findByIds returns only 1 record — docId2 was not found
            tenderDocumentRepository.findByIds([docId1.toString(), docId2.toString()]) >> [doc1]

        when: "resolving document IDs"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "NotFoundException thrown with document not found code"
            def ex = thrown(NotFoundException)
            ex.code == ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE
    }

    def "TC-A-R03: should throw BadRequestException when document belongs to a different tender"() {
        given: "a request with a document that belongs to another tender"
            def docId = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def request = buildRequest([docId])

            def doc = buildDocumentRecord(docId.toString(), "other-tender-999", "ready")
            tenderDocumentRepository.findByIds([docId.toString()]) >> [doc]

        when: "resolving document IDs"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "BadRequestException thrown with documentNotOwned code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED_CODE
            ex.message.contains(docId.toString())
            ex.message.contains(TENDER_ID)
    }

    def "TC-A-R04: should throw BadRequestException when document status is processing (not ready)"() {
        given: "a request with a document that is still processing"
            def docId = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def request = buildRequest([docId])

            def doc = buildDocumentRecord(docId.toString(), TENDER_ID, "processing")
            tenderDocumentRepository.findByIds([docId.toString()]) >> [doc]

        when: "resolving document IDs"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "BadRequestException thrown with documentNotReady code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_DOC_NOT_READY_CODE
            ex.message.contains(docId.toString())
            ex.message.contains("processing")
    }

    def "TC-A-R05: should throw BadRequestException when document status is failed"() {
        given: "a request with a failed document"
            def docId = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def request = buildRequest([docId])

            def doc = buildDocumentRecord(docId.toString(), TENDER_ID, "failed")
            tenderDocumentRepository.findByIds([docId.toString()]) >> [doc]

        when: "resolving document IDs"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "BadRequestException thrown with documentNotReady code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_DOC_NOT_READY_CODE
            ex.message.contains(docId.toString())
            ex.message.contains("failed")
    }

    def "TC-A-R06: should fall through to auto-resolve when explicit document list is empty"() {
        given: "a request with an empty documentIds list"
            def request = new ExtractionCreateRequest()
            request.setEntityId(UUID.randomUUID())
            request.setDocumentIds([])  // empty list — triggers auto-resolve

            def readyDoc = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")
            tenderDocumentRepository.findByTenderId(TENDER_ID, TenderDocumentStatus.READY.getValue(), 200, null, null) >> [readyDoc]

        when: "resolving document IDs"
            def result = adapter.resolveDocumentIds(TENDER_ID, request)

        then: "auto-resolve path is executed — no call to findByIds"
            0 * tenderDocumentRepository.findByIds(_)

        and: "the ready document's ID is returned"
            result == [readyDoc.getId()]
    }

    def "TC-A-R07: should throw BadRequestException when document IDs come from two different tenders"() {
        given: "a request with two documents belonging to different tenders"
            def docId1 = UUID.fromString("aaaa0001-0000-0000-0000-000000000001")
            def docId2 = UUID.fromString("bbbb0002-0000-0000-0000-000000000002")
            def request = buildRequest([docId1, docId2])

            def doc1 = buildDocumentRecord(docId1.toString(), TENDER_ID, "ready")
            def doc2 = buildDocumentRecord(docId2.toString(), "other-tender-777", "ready")  // belongs to different tender
            tenderDocumentRepository.findByIds([docId1.toString(), docId2.toString()]) >> [doc1, doc2]

        when: "resolving document IDs"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "BadRequestException thrown because doc2 does not belong to TENDER_ID"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_DOC_NOT_OWNED_CODE
            ex.message.contains(docId2.toString())
            ex.message.contains(TENDER_ID)
    }

    def "TC-A-R08: should fall through to auto-resolve when documentIds is empty list"() {
        given: "a request with an empty documentIds list (the API-generated default)"
            def request = new ExtractionCreateRequest()
            request.setEntityId(UUID.randomUUID())
            request.setDocumentIds([])  // empty list — same auto-resolve path as no explicit IDs

            def readyDoc = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")
            tenderDocumentRepository.findByTenderId(TENDER_ID, TenderDocumentStatus.READY.getValue(), 200, null, null) >> [readyDoc]

        when: "resolving document IDs"
            def result = adapter.resolveDocumentIds(TENDER_ID, request)

        then: "auto-resolve path is executed — findByIds is never called"
            0 * tenderDocumentRepository.findByIds(_)

        and: "the ready document ID is returned"
            result == [readyDoc.getId()]
    }

    // ==================== resolveDocumentIds — auto-resolve ====================

    def "TC-A-A01: should return list of 3 IDs when auto-resolve finds 3 ready documents"() {
        given: "a request with no document IDs (empty list triggers auto-resolve)"
            def request = new ExtractionCreateRequest()
            request.setEntityId(UUID.randomUUID())
            request.setDocumentIds([])

            def doc1 = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")
            def doc2 = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")
            def doc3 = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")
            tenderDocumentRepository.findByTenderId(TENDER_ID, TenderDocumentStatus.READY.getValue(), 200, null, null) >> [doc1, doc2, doc3]

        when: "resolving document IDs via auto-resolve"
            def result = adapter.resolveDocumentIds(TENDER_ID, request)

        then: "all 3 document IDs are returned"
            result.size() == 3
            result.containsAll([doc1.getId(), doc2.getId(), doc3.getId()])
    }

    def "TC-A-A02: should throw BadRequestException when no ready documents are available"() {
        given: "a request with empty documentIds list and no ready docs in DB"
            def request = new ExtractionCreateRequest()
            request.setEntityId(UUID.randomUUID())
            request.setDocumentIds([])

            tenderDocumentRepository.findByTenderId(TENDER_ID, TenderDocumentStatus.READY.getValue(), 200, null, null) >> []

        when: "resolving document IDs via auto-resolve"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "BadRequestException thrown with noReadyDocuments code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_NO_READY_DOCS_CODE
            ex.message.contains(TENDER_ID)
    }

    def "TC-A-A03: should call findByTenderId with correct params during auto-resolve"() {
        given: "a request with empty documentIds triggering auto-resolve"
            def request = new ExtractionCreateRequest()
            request.setEntityId(UUID.randomUUID())
            request.setDocumentIds([])

            def readyDoc = buildDocumentRecord(UUID.randomUUID().toString(), TENDER_ID, "ready")

        when: "resolving document IDs via auto-resolve"
            adapter.resolveDocumentIds(TENDER_ID, request)

        then: "findByTenderId is called with exactly the right parameters"
            1 * tenderDocumentRepository.findByTenderId(
                TENDER_ID,
                TenderDocumentStatus.READY.getValue(),
                200,
                null,
                null
            ) >> [readyDoc]
    }

    // ==================== validateFieldNames ====================

    def "TC-A-FN01: should return the same list when all field names are valid"() {
        given: "a list of valid tender field names"
            def fields = ["closing_date", "location", "scope_of_work"]

        when: "validating field names"
            def result = adapter.validateFieldNames(fields)

        then: "the same list is returned without modification"
            result == ["closing_date", "location", "scope_of_work"]
    }

    def "TC-A-FN02: should throw BadRequestException when one field name is invalid"() {
        given: "a list containing an unrecognised field name"
            def fields = ["closing_date", "invalid_field_xyz"]

        when: "validating field names"
            adapter.validateFieldNames(fields)

        then: "BadRequestException thrown with invalidField code"
            def ex = thrown(BadRequestException)
            ex.code == ApiErrorMessages.EXTRACTION_INVALID_FIELD_CODE
            ex.message.contains("invalid_field_xyz")
            ex.message.contains(EntityType.TENDER.getValue())
    }

    def "TC-A-FN03: should return empty list when fields is null"() {
        when: "validating null fields"
            def result = adapter.validateFieldNames(null)

        then: "empty list is returned — no exception"
            result == []
    }

    def "TC-A-FN04: should return empty list when fields list is empty"() {
        when: "validating an empty fields list"
            def result = adapter.validateFieldNames([])

        then: "empty list is returned — empty means no filter"
            result == []
    }

    def "TC-A-FN05: should accept every TenderFieldName enum value without throwing"() {
        given: "the complete set of valid tender field name string values"
            def allEnumValues = TenderFieldName.values().collect { it.getValue() }

        expect: "every enum value passes validation individually"
            TenderFieldName.values().each { enumValue ->
                def result = adapter.validateFieldNames([enumValue.getValue()])
                assert result == [enumValue.getValue()], "Expected [${enumValue.getValue()}] but got $result"
            }

        and: "all 17 enum values are covered — if this count changes, the test must be updated"
            allEnumValues.size() == 17

        and: "the enum includes the expected canonical field names"
            allEnumValues.containsAll([
                "name",
                "reference_number",
                "location",
                "scope_of_work",
                "delivery_method",
                "currency",
                "closing_date",
                "events",
                "start_date",
                "completion_date",
                "inquiry_deadline",
                "submission_method",
                "submission_url",
                "bonds",
                "conditions",
                "parties",
                "liquidated_damages"
            ])
    }

    // ==================== Helper methods ====================

    private static TendersRecord buildTendersRecord(String id, String companyId, String status) {
        def record = new TendersRecord()
        record.setId(id)
        record.setCompanyId(companyId)
        record.setName("Test Tender")
        record.setStatus(status)
        record.setCreatedBy("user-1")
        record.setVersion(0)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static TenderDocumentsRecord buildDocumentRecord(String id, String tenderId, String status) {
        def record = new TenderDocumentsRecord()
        record.setId(id)
        record.setTenderId(tenderId)
        record.setCompanyId("42")
        record.setFileName("document-${id}.pdf")
        record.setContentType("application/pdf")
        record.setFileSize(2048L)
        record.setS3Key("tenders/42/${tenderId}/${id}/document.pdf")
        record.setStatus(status)
        return record
    }

    private static ExtractionCreateRequest buildRequest(List<UUID> documentIds) {
        def request = new ExtractionCreateRequest()
        request.setEntityId(UUID.randomUUID())
        request.setDocumentIds(documentIds)
        return request
    }
}
