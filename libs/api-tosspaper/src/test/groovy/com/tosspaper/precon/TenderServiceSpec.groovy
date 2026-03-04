package com.tosspaper.precon

import com.tosspaper.common.DuplicateException
import com.tosspaper.common.NotFoundException
import com.tosspaper.precon.generated.model.Tender
import com.tosspaper.precon.generated.model.TenderCreateRequest
import com.tosspaper.precon.generated.model.SortDirection
import com.tosspaper.precon.generated.model.SortField
import com.tosspaper.precon.generated.model.TenderStatus
import com.tosspaper.precon.generated.model.TenderUpdateRequest
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

    def "should create tender and return result with version"() {
        given: "a valid create request"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("Bridge RFP")
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

        and: "result contains tender and version"
            result.tender().id != null
            result.tender().name == "Bridge RFP"
            result.version() == 0
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

    // ==================== createTender — null name (TOS-45) ====================

    def "should create tender with null name and return result with name=null"() {
        given: "a create request with name=null"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName(null)
            def record = createNullNameRecord("tender-null-1", companyId.toString())
            def insertedRecord = createNullNameRecord("tender-null-1", companyId.toString())
            def dto = createTenderDtoNullName("tender-null-1")

        when: "creating a tender with no name"
            def result = service.createTender(companyId, request)

        then: "mapper converts request to record (name propagates as null)"
            1 * tenderMapper.toRecord(request, "1", "test-user") >> record

        and: "repository inserts the record successfully"
            1 * tenderRepository.insert(record) >> insertedRecord

        and: "mapper converts to DTO with null name"
            1 * tenderMapper.toDto(insertedRecord) >> dto

        and: "result is returned with name=null and a valid version"
            result != null
            result.tender().name == null
            result.version() == 0
    }

    def "should create tender with name omitted (null) without throwing DuplicateException"() {
        given: "a create request that has no name set at all (field omitted by client)"
            def companyId = 1L
            def request = new TenderCreateRequest()
            // name is never set — it is null by default
            def record = createNullNameRecord("tender-omit-1", companyId.toString())
            def insertedRecord = createNullNameRecord("tender-omit-1", companyId.toString())
            def dto = createTenderDtoNullName("tender-omit-1")

        when: "creating a tender with name omitted"
            def result = service.createTender(companyId, request)

        then: "mapper and repository are called normally — null name is not special-cased"
            1 * tenderMapper.toRecord(request, "1", "test-user") >> record
            1 * tenderRepository.insert(record) >> insertedRecord
            1 * tenderMapper.toDto(insertedRecord) >> dto

        and: "DuplicateKeyException is NOT thrown — null names do not trigger the unique index"
            noExceptionThrown()

        and: "result contains name=null"
            result.tender().name == null
    }

    def "should not reach DuplicateKeyException catch block when name is null"() {
        given: "two create requests both with null name for the same company"
            def companyId = 1L
            def request1 = new TenderCreateRequest()
            request1.setName(null)
            def request2 = new TenderCreateRequest()
            request2.setName(null)
            def record1 = createNullNameRecord("tender-null-a", companyId.toString())
            def record2 = createNullNameRecord("tender-null-b", companyId.toString())
            def inserted1 = createNullNameRecord("tender-null-a", companyId.toString())
            def inserted2 = createNullNameRecord("tender-null-b", companyId.toString())
            def dto1 = createTenderDtoNullName("tender-null-a")
            def dto2 = createTenderDtoNullName("tender-null-b")

        when: "creating both tenders"
            def result1 = service.createTender(companyId, request1)
            def result2 = service.createTender(companyId, request2)

        then: "both succeed without any DuplicateKeyException — null names are NULLS DISTINCT"
            1 * tenderMapper.toRecord(request1, "1", "test-user") >> record1
            1 * tenderRepository.insert(record1) >> inserted1
            1 * tenderMapper.toDto(inserted1) >> dto1
            1 * tenderMapper.toRecord(request2, "1", "test-user") >> record2
            1 * tenderRepository.insert(record2) >> inserted2
            1 * tenderMapper.toDto(inserted2) >> dto2
            noExceptionThrown()
            result1.tender().name == null
            result2.tender().name == null
    }

    def "should still throw DuplicateException when non-null name conflicts"() {
        given: "a request with a non-null duplicate name"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("Existing RFP")
            def record = createRecord("tender-dup", companyId.toString())

        when: "creating a tender with a duplicate name"
            service.createTender(companyId, request)

        then: "mapper converts the request"
            1 * tenderMapper.toRecord(request, "1", "test-user") >> record

        and: "repository throws DuplicateKeyException (unique constraint on non-null name)"
            1 * tenderRepository.insert(record) >> { throw new DuplicateKeyException("unique constraint") }

        and: "DuplicateException is re-thrown with message referencing the name"
            def ex = thrown(DuplicateException)
            ex != null
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
            service.listTenders(companyId, 20, null, null, null, null, TenderStatus.PENDING)

        then: "repository is called with status"
            1 * tenderRepository.findByCompanyId("1", { it.status == "pending" }) >> []
            1 * tenderMapper.toDtoList([]) >> []
    }

    def "should set cursor when more results exist"() {
        given: "repo returns limit+1 records (indicating more pages)"
            def companyId = 1L
            // limit=2, repo returns 3 records → hasMore=true
            def r1 = createRecord("t1", "1")
            def r2 = createRecord("t2", "1")
            def r3 = createRecord("t3", "1")
            def records = [r1, r2, r3]
            def dtos = [createTenderDto("t1"), createTenderDto("t2")]

        when: "listing with limit 2"
            def result = service.listTenders(companyId, 2, null, null, null, null, null)

        then: "repository returns limit+1 records"
            1 * tenderRepository.findByCompanyId("1", { it.limit == 2 }) >> records

        and: "mapper receives trimmed list"
            1 * tenderMapper.toDtoList([r1, r2]) >> dtos

        and: "pagination has cursor"
            result.data.size() == 2
            result.pagination.cursor != null
    }

    def "should set null cursor when no more results"() {
        given: "repo returns exactly limit records (no extra)"
            def companyId = 1L
            def r1 = createRecord("t1", "1")
            def r2 = createRecord("t2", "1")
            def records = [r1, r2]
            def dtos = [createTenderDto("t1"), createTenderDto("t2")]

        when: "listing with limit 2"
            def result = service.listTenders(companyId, 2, null, null, null, null, null)

        then: "repository returns exactly limit records"
            1 * tenderRepository.findByCompanyId("1", { it.limit == 2 }) >> records

        and: "mapper receives all records"
            1 * tenderMapper.toDtoList(records) >> dtos

        and: "pagination has null cursor"
            result.data.size() == 2
            result.pagination.cursor == null
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

    def "should return tender result with version"() {
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

        and: "result contains tender and version"
            result.tender().name == "Test Tender"
            result.version() == 0
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

    def "should update tender name and return result with version"() {
        given: "an existing tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def updated = createRecord(tenderId, companyId.toString())
            updated.setName("New Name")
            updated.setVersion(1)
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

        and: "result has updated name and version"
            result.tender().name == "New Name"
            result.version() == 1
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

    def "should validate status transition pending to submitted"() {
        given: "a pending tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def updated = createRecord(tenderId, companyId.toString())
            updated.setStatus("submitted")
            updated.setVersion(1)
            def request = new TenderUpdateRequest()
            request.setStatus(TenderStatus.SUBMITTED)
            def dto = createTenderDto(tenderId)
            dto.setStatus(TenderStatus.SUBMITTED)

        when: "updating status to submitted"
            def result = service.updateTender(companyId, tenderId, request, '"v0"')

        then: "no exception"
            1 * tenderRepository.findById(tenderId) >> existing
            1 * tenderMapper.updateRecord(request, existing)
            1 * tenderRepository.update(tenderId, existing, 0) >> 1
            1 * tenderRepository.findById(tenderId) >> updated
            1 * tenderMapper.toDto(updated) >> dto
            result.tender().status == TenderStatus.SUBMITTED
    }

    def "should throw InvalidStatusTransitionException for invalid transition"() {
        given: "a pending tender"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def request = new TenderUpdateRequest()
            request.setStatus(TenderStatus.WON)

        when: "updating status from pending to won"
            service.updateTender(companyId, tenderId, request, '"v0"')

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> existing

        and: "InvalidStatusTransitionException thrown"
            thrown(InvalidStatusTransitionException)
    }

    // ==================== deleteTender ====================

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
        record.setStatus("pending")
        record.setCreatedBy("user-1")
        record.setVersion(0)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static Tender createTenderDto(String id) {
        def tender = new Tender()
        tender.setId(UUID.fromString(id.length() == 36 ? id : "00000000-0000-0000-0000-000000000001"))
        tender.setName("Test Tender")
        tender.setStatus(TenderStatus.PENDING)
        tender.setCreatedAt(OffsetDateTime.now())
        tender.setUpdatedAt(OffsetDateTime.now())
        tender.setCreatedBy(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        return tender
    }

    private static TendersRecord createNullNameRecord(String id, String companyId) {
        def record = new TendersRecord()
        record.setId(id)
        record.setCompanyId(companyId)
        record.setName(null)
        record.setStatus("pending")
        record.setCreatedBy("user-1")
        record.setVersion(0)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static Tender createTenderDtoNullName(String id) {
        def tender = new Tender()
        tender.setId(UUID.fromString(id.length() == 36 ? id : "00000000-0000-0000-0000-000000000001"))
        tender.setName(null)
        tender.setStatus(TenderStatus.PENDING)
        tender.setCreatedAt(OffsetDateTime.now())
        tender.setUpdatedAt(OffsetDateTime.now())
        tender.setCreatedBy(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        return tender
    }
}
