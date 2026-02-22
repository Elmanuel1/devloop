package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.BadRequestException
import com.tosspaper.common.NotFoundException
import com.tosspaper.generated.model.Tender
import com.tosspaper.generated.model.TenderCreateRequest
import com.tosspaper.generated.model.TenderStatus
import com.tosspaper.generated.model.TenderUpdateRequest
import com.tosspaper.models.jooq.tables.records.TendersRecord
import spock.lang.Specification

import java.time.OffsetDateTime

class TenderServiceSpec extends Specification {

    TenderRepository tenderRepository
    TenderMapper tenderMapper
    ObjectMapper objectMapper
    TenderServiceImpl service

    def setup() {
        tenderRepository = Mock()
        tenderMapper = Mock()
        objectMapper = new ObjectMapper()
        service = new TenderServiceImpl(tenderRepository, tenderMapper, objectMapper)
    }

    // ==================== createTender ====================

    def "should create tender with all fields and return response"() {
        given: "a valid create request"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("Bridge RFP")
            request.setCurrency("CAD")
            request.setDeliveryMethod("lump_sum")
            def record = createRecord("tender-1", companyId.toString())
            def dto = createTenderDto("tender-1")
            dto.setName("Bridge RFP")

        when: "creating a tender"
            def result = service.createTender(companyId, request, "user-1")

        then: "repository checks for duplicates"
            1 * tenderRepository.existsByCompanyIdAndName("1", "bridge rfp") >> false

        and: "repository inserts the tender"
            1 * tenderRepository.insert("1", _ as Map) >> record

        and: "mapper converts to DTO"
            1 * tenderMapper.toDto(record) >> dto

        and: "result is returned"
            result.id != null
            result.name == "Bridge RFP"
    }

    def "should throw DuplicateNameException when name exists for company (case-insensitive)"() {
        given: "a request with a duplicate name"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("bridge rfp")

        when: "creating a tender"
            service.createTender(companyId, request, "user-1")

        then: "repository finds duplicate"
            1 * tenderRepository.existsByCompanyIdAndName("1", "bridge rfp") >> true

        and: "DuplicateNameException is thrown"
            thrown(DuplicateNameException)
    }

    def "should set status to draft regardless of input"() {
        given: "a request (status is not settable via create)"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("Test Tender")
            def record = createRecord("tender-1", companyId.toString())
            record.setStatus("draft")
            def dto = createTenderDto("tender-1")
            dto.setStatus(TenderStatus.DRAFT)

        when: "creating a tender"
            def result = service.createTender(companyId, request, "user-1")

        then: "no duplicates found"
            1 * tenderRepository.existsByCompanyIdAndName("1", "test tender") >> false

        and: "repository insert is called"
            1 * tenderRepository.insert("1", _ as Map) >> record

        and: "mapper converts to DTO"
            1 * tenderMapper.toDto(record) >> dto

        and: "result status is draft"
            result.status == TenderStatus.DRAFT
    }

    def "should throw BadRequestException when name is missing"() {
        given: "a request with no name"
            def companyId = 1L
            def request = new TenderCreateRequest()

        when: "creating a tender"
            service.createTender(companyId, request, "user-1")

        then: "BadRequestException is thrown"
            thrown(BadRequestException)
    }

    def "should throw BadRequestException when name is blank"() {
        given: "a request with blank name"
            def companyId = 1L
            def request = new TenderCreateRequest()
            request.setName("  ")

        when: "creating a tender"
            service.createTender(companyId, request, "user-1")

        then: "BadRequestException is thrown"
            thrown(BadRequestException)
    }

    // ==================== listTenders ====================

    def "should return paginated list of tenders"() {
        given: "a company with tenders"
            def companyId = 1L
            def query = TenderQuery.builder()
                .limit(20)
                .sortBy("created_at")
                .sortDirection("desc")
                .build()
            def records = [createRecord("t1", "1"), createRecord("t2", "1")]
            def dtos = [createTenderDto("t1"), createTenderDto("t2")]

        when: "listing tenders"
            def result = service.listTenders(companyId, query)

        then: "repository returns records"
            1 * tenderRepository.findByCompanyId("1", query) >> records

        and: "mapper converts"
            1 * tenderMapper.toDtoList(records) >> dtos

        and: "result has items"
            result.data.size() == 2
    }

    def "should pass search term to repository"() {
        given: "a query with search"
            def companyId = 1L
            def query = TenderQuery.builder()
                .search("bridge")
                .limit(20)
                .sortBy("created_at")
                .sortDirection("desc")
                .build()

        when: "listing tenders"
            service.listTenders(companyId, query)

        then: "repository is called with search"
            1 * tenderRepository.findByCompanyId("1", { it.search == "bridge" }) >> []
            1 * tenderMapper.toDtoList([]) >> []
    }

    def "should pass status filter to repository"() {
        given: "a query with status"
            def companyId = 1L
            def query = TenderQuery.builder()
                .status("draft")
                .limit(20)
                .sortBy("created_at")
                .sortDirection("desc")
                .build()

        when: "listing tenders"
            service.listTenders(companyId, query)

        then: "repository is called with status"
            1 * tenderRepository.findByCompanyId("1", { it.status == "draft" }) >> []
            1 * tenderMapper.toDtoList([]) >> []
    }

    def "should return empty list when no tenders exist"() {
        given: "no tenders"
            def companyId = 1L
            def query = TenderQuery.builder()
                .limit(20)
                .sortBy("created_at")
                .sortDirection("desc")
                .build()

        when: "listing tenders"
            def result = service.listTenders(companyId, query)

        then: "repository returns empty"
            1 * tenderRepository.findByCompanyId("1", query) >> []
            1 * tenderMapper.toDtoList([]) >> []

        and: "result is empty"
            result.data.isEmpty()
            result.pagination.cursor == null
    }

    // ==================== getTender ====================

    def "should return tender with all fields"() {
        given: "a tender exists"
            def companyId = 1L
            def tenderId = "tender-1"
            def record = createRecord(tenderId, companyId.toString())
            def dto = createTenderDto(tenderId)

        when: "getting tender"
            def result = service.getTender(companyId, tenderId)

        then: "repository returns record"
            1 * tenderRepository.findById(tenderId) >> Optional.of(record)

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

        then: "repository returns empty"
            1 * tenderRepository.findById(tenderId) >> Optional.empty()

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
            1 * tenderRepository.findById(tenderId) >> Optional.of(record)

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
            def result = service.updateTender(companyId, tenderId, request, 0)

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> Optional.of(existing)

        and: "name uniqueness checked"
            1 * tenderRepository.existsByCompanyIdAndNameExcludingSelf("1", "new name", tenderId) >> false

        and: "update executes"
            1 * tenderRepository.update(tenderId, _ as Map, 0) >> 1

        and: "updated record is fetched"
            1 * tenderRepository.findById(tenderId) >> Optional.of(updated)
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
            service.updateTender(companyId, tenderId, request, 0)

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> Optional.of(existing)
            1 * tenderRepository.existsByCompanyIdAndNameExcludingSelf("1", "new name", tenderId) >> false

        and: "update returns 0 rows"
            1 * tenderRepository.update(tenderId, _, 0) >> 0

        and: "StaleVersionException thrown"
            thrown(StaleVersionException)
    }

    def "should throw DuplicateNameException when name conflicts"() {
        given: "an existing tender with name conflict"
            def companyId = 1L
            def tenderId = "tender-1"
            def existing = createRecord(tenderId, companyId.toString())
            def request = new TenderUpdateRequest()
            request.setName("taken")

        when: "updating with taken name"
            service.updateTender(companyId, tenderId, request, 0)

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> Optional.of(existing)

        and: "name conflict detected"
            1 * tenderRepository.existsByCompanyIdAndNameExcludingSelf("1", "taken", tenderId) >> true

        and: "DuplicateNameException thrown"
            thrown(DuplicateNameException)
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
            def result = service.updateTender(companyId, tenderId, request, 0)

        then: "no exception"
            1 * tenderRepository.findById(tenderId) >> Optional.of(existing)
            1 * tenderRepository.update(tenderId, _, 0) >> 1
            1 * tenderRepository.findById(tenderId) >> Optional.of(updated)
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
            service.updateTender(companyId, tenderId, request, 0)

        then: "repository finds existing"
            1 * tenderRepository.findById(tenderId) >> Optional.of(existing)

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
            1 * tenderRepository.findById(tenderId) >> Optional.of(record)

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
            1 * tenderRepository.findById(tenderId) >> Optional.of(record)

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
            1 * tenderRepository.findById(tenderId) >> Optional.of(record)

        and: "CannotDeleteException thrown"
            thrown(CannotDeleteException)
    }

    def "should throw NotFoundException when tender not found for delete"() {
        given: "tender does not exist"
            def companyId = 1L
            def tenderId = "nonexistent"

        when: "deleting"
            service.deleteTender(companyId, tenderId)

        then: "repository returns empty"
            1 * tenderRepository.findById(tenderId) >> Optional.empty()

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
