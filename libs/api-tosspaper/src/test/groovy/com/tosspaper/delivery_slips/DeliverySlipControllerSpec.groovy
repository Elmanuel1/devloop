package com.tosspaper.delivery_slips

import com.tosspaper.generated.model.DeliverySlip
import com.tosspaper.generated.model.DeliverySlipList
import com.tosspaper.generated.model.Pagination
import org.springframework.http.HttpStatus
import spock.lang.Specification

class DeliverySlipControllerSpec extends Specification {

    DeliverySlipService deliverySlipService
    DeliverySlipController controller

    def setup() {
        deliverySlipService = Mock()
        controller = new DeliverySlipController(deliverySlipService)
    }

    // ==================== getDeliverySlips ====================

    def "getDeliverySlips returns OK with delivery slip list"() {
        given: "valid context and query parameters"
            def xContextId = "123"
            def projectId = "proj-1"
            def purchaseOrderId = "po-1"
            def poNumber = "PO-001"
            def limit = 10
            def cursor = null
            def search = "test"

            def deliverySlipList = new DeliverySlipList()
            deliverySlipList.setData([createDeliverySlip("slip-1"), createDeliverySlip("slip-2")])
            deliverySlipList.setPagination(new Pagination())

        when: "calling getDeliverySlips"
            def response = controller.getDeliverySlips(xContextId, projectId, purchaseOrderId, poNumber, limit, cursor, search)

        then: "service is called with parsed company ID and parameters"
            1 * deliverySlipService.getDeliverySlips(123L, projectId, purchaseOrderId, poNumber, search, limit, cursor) >> deliverySlipList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery slips"
            with(response.body) {
                data.size() == 2
                data[0].id == "slip-1"
                data[1].id == "slip-2"
            }
    }

    def "getDeliverySlips handles null optional parameters"() {
        given: "only required context parameter"
            def xContextId = "456"
            def deliverySlipList = new DeliverySlipList()
            deliverySlipList.setData([])
            deliverySlipList.setPagination(new Pagination())

        when: "calling getDeliverySlips with null optional parameters"
            def response = controller.getDeliverySlips(xContextId, null, null, null, null, null, null)

        then: "service is called with null parameters"
            1 * deliverySlipService.getDeliverySlips(456L, null, null, null, null, null, null) >> deliverySlipList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "getDeliverySlips returns empty list when no slips found"() {
        given: "a company with no delivery slips"
            def xContextId = "789"
            def deliverySlipList = new DeliverySlipList()
            deliverySlipList.setData([])
            deliverySlipList.setPagination(new Pagination())

        when: "calling getDeliverySlips"
            def response = controller.getDeliverySlips(xContextId, null, null, null, null, null, null)

        then: "service returns empty list"
            1 * deliverySlipService.getDeliverySlips(789L, null, null, null, null, null, null) >> deliverySlipList

        and: "response contains empty list"
            response.statusCode == HttpStatus.OK
            response.body.data.isEmpty()
    }

    // ==================== getDeliverySlipById ====================

    def "getDeliverySlipById returns OK with delivery slip"() {
        given: "valid context and slip ID"
            def xContextId = "123"
            def slipId = "slip-123"
            def deliverySlip = createDeliverySlip(slipId)

        when: "calling getDeliverySlipById"
            def response = controller.getDeliverySlipById(xContextId, slipId)

        then: "service is called with parsed company ID and slip ID"
            1 * deliverySlipService.getDeliverySlipById(123L, slipId) >> deliverySlip

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery slip"
            response.body.id == slipId
    }

    def "getDeliverySlipById propagates exception from service"() {
        given: "a slip that does not exist"
            def xContextId = "123"
            def slipId = "non-existent"

        when: "calling getDeliverySlipById"
            controller.getDeliverySlipById(xContextId, slipId)

        then: "service throws exception"
            1 * deliverySlipService.getDeliverySlipById(123L, slipId) >> {
                throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Delivery slip not found")
            }

        and: "exception is propagated"
            thrown(org.springframework.web.server.ResponseStatusException)
    }

    // ==================== Helper Methods ====================

    private static DeliverySlip createDeliverySlip(String id) {
        def slip = new DeliverySlip()
        slip.id = id
        return slip
    }
}
