package com.tosspaper.precon

import com.tosspaper.common.DuplicateException
import com.tosspaper.common.NotFoundException
import com.tosspaper.generated.model.Tender
import com.tosspaper.generated.model.TenderCreateRequest
import com.tosspaper.generated.model.TenderSortDirection
import com.tosspaper.generated.model.TenderSortField
import com.tosspaper.generated.model.TenderStatus
import com.tosspaper.generated.model.TenderUpdateRequest
import com.tosspaper.models.exception.CannotDeleteException
import com.tosspaper.models.exception.IfMatchRequiredException
import com.tosspaper.models.exception.InvalidStatusTransitionException
import com.tosspaper.models.exception.StaleVersionException
import com.tosspaper.models.jooq.tables.records.TendersRecord
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import spock.lang.Specification

import java.time.OffsetDateTime

class TenderServiceSpec extends Specification {

    TenderRepository tenderRepository
    TenderMapper tenderMapper
    TenderServiceImpl service

    def setup() {
        tenderRepository = Mock()
        tenderMapper = Mock()
        service = new TenderServiceImpl(tenderRepository, tenderMapper)

        // Set up security context
        def auth = new TestingAuthenticationToken("test-user", null)
        SecurityContextHolder.getContext().setAuthentication(auth)
    }

    def cleanup() {
        SecurityContextHolder.clearContext()
    }

    // ==================== createTender ====================

    def "should create tender and return response"() {
        given: "a valid create request"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("Bridge RFP")
            request.setCurrency("CAD")
            request.setDeliveryMethod("lump_sum")
            def record = createRecord("tender-1", companyId.toString())
            def insertedRecord = createRecord("tender-1", companyId.toString())
            insertedRecord.setName("Bridge RFP")
            def dto = createTenderDto("tender-1")
            dto.setName("Bridge RFP")

        when: "creating a tender"
            def result = service.createTender(companyId, request)

        then: "mapper converts request to record"
            1 * tenderMapper.toRecord(request, "1", "test-user") >> record

        and: "repository inserts the record"
            1 * tenderRepository.insert(record) >> insertedRecord

        and: "mapper converts to DTO"
            1 * tenderMapper.toDto(insertedRecord) >> dto

        and: "result is returned"
            result.id != null
            result.name == "Bridge RFP"
    }

    def "should throw DuplicateException when name exists for company"() {
        given: "a request with a duplicate name"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("bridge rfp")
            def record = createRecord("tender-1", companyId.toString())

        when: "creating a tender"
            service.createTender(companyId, request)

        then: "mapper converts request to record"
            1 * tenderMapper.toRecord(request, "1", "test-user") >> record

        and: "repository throws DuplicateKeyException"
            1 * tenderRepository.insert(record) >> { throw new DuplicateKeyException("duplicate") }

        and: "DuplicateException is thrown"
            thrown(DuplicateException)
    }

    // ==================== listTenders ====================

    def "should return paginated list of tenders"() {
        given: "a company with tenders"
            def companyId = 1L
            def records = [createRecord("t1", "1"), createRecord("t2", "1")]
            def dtos = [createTenderDto("t1"), createTenderDto("t2")]

        when: "listing tenders"
            def result = service.listTenders(companyId, 20, null, null, null, null, null)

        then: "repository returns records"
            1 * tenderRepository.findByCompanyId("1", _ as TenderQuery) >> records

        and: "mapper converts"
            1 * tenderMapper.toDtoList(records) >> dtos

        and: "result has items"
            result.data.size() == 2
    }

    def "should pass search term to repository"() {
        given: "a company"
            def companyId = 1L

        when: "listing tenders with search"
            service.listTenders(companyId, 20, null, "bridge", null, null, null)

        then: "repository is called with search"
            1 * tenderRepository.findByCompanyId("1", { it.search == "bridge" }) >> []
            1 * tenderMapper.toDtoList([]) >> []
    }

    def "should pass status filter to repository"() {
        given: "a company"
            def companyId = 1L

        when: "listing tenders with status"
            service.listTenders(companyId, 20, null, null, null, null, TenderStatus.DRAFT)

        then: "repository is called with status"
            1 * tenderRepository.findByCompanyId("1", { it.status == "draft" }) >> []
            1 * tenderMapper.toDtoList([]) >> []
    }

    def "should return empty list when no tenders exist"() {
        given: "no tenders"
            def companyId = 1L

        when: "listing tenders"
            def result = service.listTenders(companyId, 20, null, null, null, null, null)

        then: "repository returns empty"
            1 * tenderRepository.findByCompanyId("1", _ as TenderQuery) >> []
            1 * tenderMapper.toDtoList([]) >> []

        and: "result is empty"
            result.data.isEmpty()
            result.pagination.cursor == null
    }

    // ==================== getTender ====================

    def "should return tender"() {
        given: "a tender exists"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, companyId.toString())
            def dto = createTenderDto(tenderId)

        when: "getting tender"
            def result = service.getTender(companyId, tenderId)

        then: "repository returns record"
            1 * tenderRepository.findById(tenderId) >> record

        and: "mapper converts"
            1 * tenderMapper.toDto(record) >> dto

        and: "result matches"
            result.name == "Test Tender"
    }

    def "should throw NotFoundException when tender not found"() {
        given: "tender does not exist"
            def companyId = 1L
            def tenderId = "nonexistent"

        when: "getting tender"
            service.getTender(companyId, tenderId)

        then: "repository throws NotFoundException"
            1 * tenderRepository.findById(tenderId) >> { throw new NotFoundException("api.tender.notFound", "Tender not found") }

        and: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    def "should throw NotFoundException when tender belongs to other company"() {
        given: "a tender from a different company"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, "999")

        when: "getting tender"
            service.getTender(companyId, tenderId)

        then: "repository returns record with different company"
            1 * tenderRepository.findById(tenderId) >> record

        and: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    // ==================== updateTender ====================

    def "should update tender name and return updated record"() {
        given: "an existing tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def updated = createRecord(tenderId, companyId.toString())
            updated.setName("New Name")
            def request = new TenderUpdateRequest()
            request.setName("New Name")
            def dto = createTenderDto(tenderId)
            dto.setName("New Name")

        when: "updating"
            def result = service.updateTender(companyId, tenderId, request, '"v0"')

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> existing

        and: "mapper applies update"
            1 * tenderMapper.updateRecord(request, existing)

        and: "update executes"
            1 * tenderRepository.update(tenderId, existing, 0) >> 1

        and: "updated record is fetched"
            1 * tenderRepository.findById(tenderId) >> updated
            1 * tenderMapper.toDto(updated) >> dto

        and: "result has updated name"
            result.name == "New Name"
    }

    def "should throw StaleVersionException when version mismatch"() {
        given: "an existing tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def request = new TenderUpdateRequest()
            request.setName("New Name")

        when: "updating with stale version"
            service.updateTender(companyId, tenderId, request, '"v0"')

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> existing

        and: "mapper applies update"
            1 * tenderMapper.updateRecord(request, existing)

        and: "update returns 0 rows"
            1 * tenderRepository.update(tenderId, existing, 0) >> 0

        and: "StaleVersionException thrown"
            thrown(StaleVersionException)
    }

    def "should throw DuplicateException when name conflicts on update"() {
        given: "an existing tender with name conflict"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def request = new TenderUpdateRequest()
            request.setName("taken")

        when: "updating with taken name"
            service.updateTender(companyId, tenderId, request, '"v0"')

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> existing

        and: "mapper applies update"
            1 * tenderMapper.updateRecord(request, existing)

        and: "update throws DuplicateKeyException"
            1 * tenderRepository.update(tenderId, existing, 0) >> { throw new DuplicateKeyException("duplicate") }

        and: "DuplicateException thrown"
            thrown(DuplicateException)
    }

    def "should throw IfMatchRequiredException when If-Match missing"() {
        given: "an existing tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def request = new TenderUpdateRequest()
            request.setName("New Name")

        when: "updating without If-Match"
            service.updateTender(companyId, tenderId, request, null)

        then: "IfMatchRequiredException thrown"
            thrown(IfMatchRequiredException)
    }

    def "should validate status transition draft to pending"() {
        given: "a draft tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            existing.setStatus("draft")
            def updated = createRecord(tenderId, companyId.toString())
            updated.setStatus("pending")
            def request = new TenderUpdateRequest()
            request.setStatus(TenderStatus.PENDING)
            def dto = createTenderDto(tenderId)
            dto.setStatus(TenderStatus.PENDING)

        when: "updating status to pending"
            def result = service.updateTender(companyId, tenderId, request, '"v0"')

        then: "no exception"
            1 * tenderRepository.findById(tenderId) >> existing
            1 * tenderMapper.updateRecord(request, existing)
            1 * tenderRepository.update(tenderId, existing, 0) >> 1
            1 * tenderRepository.findById(tenderId) >> updated
            1 * tenderMapper.toDto(updated) >> dto
            result.status == TenderStatus.PENDING
    }

    def "should throw InvalidStatusTransitionException for invalid transition"() {
        given: "a draft tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            existing.setStatus("draft")
            def request = new TenderUpdateRequest()
            request.setStatus(TenderStatus.WON)

        when: "updating status from draft to won"
            service.updateTender(companyId, tenderId, request, '"v0"')

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> existing

        and: "InvalidStatusTransitionException thrown"
            thrown(InvalidStatusTransitionException)
    }

    // ==================== deleteTender ====================

    def "should soft-delete draft tender"() {
        given: "a draft tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, companyId.toString())
            record.setStatus("draft")

        when: "deleting"
            service.deleteTender(companyId, tenderId)

        then: "repository finds record"
            1 * tenderRepository.findById(tenderId) >> record

        and: "soft delete called"
            1 * tenderRepository.softDelete(tenderId)
    }

    def "should soft-delete pending tender"() {
        given: "a pending tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, companyId.toString())
            record.setStatus("pending")

        when: "deleting"
            service.deleteTender(companyId, tenderId)

        then: "repository finds record"
            1 * tenderRepository.findById(tenderId) >> record

        and: "soft delete called"
            1 * tenderRepository.softDelete(tenderId)
    }

    def "should throw CannotDeleteException when status is submitted"() {
        given: "a submitted tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, companyId.toString())
            record.setStatus("submitted")

        when: "deleting"
            service.deleteTender(companyId, tenderId)

        then: "repository finds record"
            1 * tenderRepository.findById(tenderId) >> record

        and: "CannotDeleteException thrown"
            thrown(CannotDeleteException)
    }

    def "should throw NotFoundException when tender not found for delete"() {
        given: "tender does not exist"
            def companyId = 1L
            def tenderId = "nonexistent"

        when: "deleting"
            service.deleteTender(companyId, tenderId)

        then: "repository throws NotFoundException"
            1 * tenderRepository.findById(tenderId) >> { throw new NotFoundException("api.tender.notFound", "Tender not found") }

        and: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    // ==================== Helper Methods ====================

    private static TendersRecord createRecord(String id, String companyId) {
        def record = new TendersRecord()
        record.setId(id)
        record.setCompanyId(companyId)
        record.setName("Test Tender")
        record.setStatus("draft")
        record.setCreatedBy("user-1")
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static Tender createTenderDto(String id) {
        def tender = new Tender()
        tender.setId(UUID.fromString(id.length() == 36 ? id : "00000000-0000-0000-0000-000000000001"))
        tender.setName("Test Tender")
        tender.setStatus(TenderStatus.DRAFT)
        tender.setVersion(0)
        tender.setCreatedAt(OffsetDateTime.now())
        tender.setUpdatedAt(OffsetDateTime.now())
        tender.setCreatedBy("user-1")
        return tender
    }
}
