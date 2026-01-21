package com.tosspaper.delivery_slips

import com.tosspaper.generated.model.DeliverySlip
import com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import spock.lang.Specification

import java.time.OffsetDateTime

class DeliverySlipServiceSpec extends Specification {

    DeliverySlipRepository deliverySlipRepository
    DeliverySlipMapper deliverySlipMapper
    DeliverySlipServiceImpl service

    def setup() {
        deliverySlipRepository = Mock()
        deliverySlipMapper = Mock()
        service = new DeliverySlipServiceImpl(deliverySlipRepository, deliverySlipMapper)
    }

    // ==================== getDeliverySlips ====================

    def "getDeliverySlips returns paginated list with default limit"() {
        given: "a company ID"
            def companyId = 1L
            def records = [createRecord("slip-1", companyId), createRecord("slip-2", companyId)]
            def slips = [createDeliverySlip("slip-1"), createDeliverySlip("slip-2")]

        when: "fetching delivery slips without limit"
            def result = service.getDeliverySlips(companyId, null, null, null, null, null, null)

        then: "repository is called with default page size of 20"
            1 * deliverySlipRepository.findDeliverySlips(companyId, _ as DeliverySlipQuery) >> { Long cId, DeliverySlipQuery q ->
                assert q.pageSize == 20
                return records
            }

        and: "records are mapped"
            1 * deliverySlipMapper.toDtoList(records) >> slips

        and: "result contains delivery slips"
            result.data.size() == 2
            result.data[0].id == "slip-1"
            result.data[1].id == "slip-2"
    }

    def "getDeliverySlips uses provided limit"() {
        given: "a specific limit"
            def companyId = 1L
            def limit = 5

        when: "fetching delivery slips with limit"
            def result = service.getDeliverySlips(companyId, null, null, null, null, limit, null)

        then: "repository is called with provided limit"
            1 * deliverySlipRepository.findDeliverySlips(companyId, _ as DeliverySlipQuery) >> { Long cId, DeliverySlipQuery q ->
                assert q.pageSize == 5
                return []
            }
            1 * deliverySlipMapper.toDtoList([]) >> []

        and: "result is returned"
            result.data.isEmpty()
    }

    def "getDeliverySlips generates next cursor when results equal page size"() {
        given: "results that fill the page"
            def companyId = 1L
            def limit = 2
            def records = [createRecord("slip-1", companyId), createRecord("slip-2", companyId)]

        when: "fetching delivery slips"
            def result = service.getDeliverySlips(companyId, null, null, null, null, limit, null)

        then: "repository returns exactly limit records"
            1 * deliverySlipRepository.findDeliverySlips(companyId, _) >> records
            1 * deliverySlipMapper.toDtoList(records) >> [createDeliverySlip("slip-1"), createDeliverySlip("slip-2")]

        and: "next cursor is generated"
            result.pagination.cursor != null
    }

    def "getDeliverySlips returns null cursor when results less than page size"() {
        given: "results that don't fill the page"
            def companyId = 1L
            def limit = 10
            def records = [createRecord("slip-1", companyId)]

        when: "fetching delivery slips"
            def result = service.getDeliverySlips(companyId, null, null, null, null, limit, null)

        then: "repository returns less than limit"
            1 * deliverySlipRepository.findDeliverySlips(companyId, _) >> records
            1 * deliverySlipMapper.toDtoList(records) >> [createDeliverySlip("slip-1")]

        and: "no next cursor"
            result.pagination.cursor == null
    }

    def "getDeliverySlips passes filter parameters to query"() {
        given: "filter parameters"
            def companyId = 1L
            def projectId = "proj-1"
            def purchaseOrderId = "po-1"
            def poNumber = "PO-001"
            def search = "test"

        when: "fetching delivery slips with filters"
            service.getDeliverySlips(companyId, projectId, purchaseOrderId, poNumber, search, 20, null)

        then: "repository is called with all filters"
            1 * deliverySlipRepository.findDeliverySlips(companyId, _ as DeliverySlipQuery) >> { Long cId, DeliverySlipQuery q ->
                assert q.projectId == projectId
                assert q.purchaseOrderId == purchaseOrderId
                assert q.poNumber == poNumber
                assert q.search == search
                return []
            }
            1 * deliverySlipMapper.toDtoList([]) >> []
    }

    def "getDeliverySlips throws IllegalArgumentException for invalid cursor format"() {
        given: "an invalid cursor"
            def companyId = 1L
            def invalidCursor = "not-a-valid-cursor"

        when: "fetching delivery slips with invalid cursor"
            service.getDeliverySlips(companyId, null, null, null, null, 20, invalidCursor)

        then: "IllegalArgumentException is thrown"
            def ex = thrown(IllegalArgumentException)
            ex.message.contains("Invalid cursor format")
    }

    // ==================== getDeliverySlipById ====================

    def "getDeliverySlipById returns delivery slip when found and company matches"() {
        given: "an existing delivery slip"
            def companyId = 1L
            def slipId = "slip-123"
            def record = createRecord(slipId, companyId)
            def slip = createDeliverySlip(slipId)

        when: "fetching delivery slip by ID"
            def result = service.getDeliverySlipById(companyId, slipId)

        then: "repository returns record"
            1 * deliverySlipRepository.findById(slipId) >> record

        and: "record is mapped"
            1 * deliverySlipMapper.toDto(record) >> slip

        and: "result has correct ID"
            result.id == slipId
    }

    def "getDeliverySlipById throws 404 when delivery slip not found"() {
        given: "non-existent delivery slip ID"
            def companyId = 1L
            def slipId = "non-existent"

        when: "fetching delivery slip by ID"
            service.getDeliverySlipById(companyId, slipId)

        then: "repository returns null"
            1 * deliverySlipRepository.findById(slipId) >> null

        and: "ResponseStatusException with 404 is thrown"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Delivery slip not found")
    }

    def "getDeliverySlipById throws 404 when company does not match"() {
        given: "a delivery slip from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def slipId = "slip-123"
            def record = createRecord(slipId, differentCompanyId)

        when: "fetching delivery slip by ID"
            service.getDeliverySlipById(companyId, slipId)

        then: "repository returns record from different company"
            1 * deliverySlipRepository.findById(slipId) >> record

        and: "ResponseStatusException with 404 is thrown"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Delivery slip not found")

        and: "no mapping occurs"
            0 * deliverySlipMapper.toDto(_)
    }

    // ==================== Helper Methods ====================

    private static DeliverySlipsRecord createRecord(String id, Long companyId) {
        def record = new DeliverySlipsRecord()
        record.id = id
        record.companyId = companyId
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private static DeliverySlip createDeliverySlip(String id) {
        def slip = new DeliverySlip()
        slip.id = id
        return slip
    }
}
