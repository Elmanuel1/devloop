package com.tosspaper.api

import com.tosspaper.aiengine.service.DocumentMatchService
import com.tosspaper.generated.model.LinkPoRequest
import org.springframework.http.HttpStatus
import spock.lang.Specification

class DocumentMatchControllerSpec extends Specification {

    DocumentMatchService documentMatchService
    DocumentMatchController controller

    def setup() {
        documentMatchService = Mock()
        controller = new DocumentMatchController(documentMatchService)
    }

    // ==================== linkPurchaseOrder ====================

    def "linkPurchaseOrder returns ACCEPTED when successful"() {
        given: "valid context, assignedId, and link request"
            def xContextId = "123"
            def assignedId = "task-456"
            def linkPoRequest = new LinkPoRequest()
            linkPoRequest.poNumber = "PO-001"

        when: "calling linkPurchaseOrder"
            def response = controller.linkPurchaseOrder(xContextId, assignedId, linkPoRequest)

        then: "service initiates manual link"
            1 * documentMatchService.initiateManualLink(123L, assignedId, "PO-001")

        and: "response status is ACCEPTED"
            response.statusCode == HttpStatus.ACCEPTED
    }

    def "linkPurchaseOrder throws RuntimeException when service fails"() {
        given: "valid context and request, but service fails"
            def xContextId = "123"
            def assignedId = "task-456"
            def linkPoRequest = new LinkPoRequest()
            linkPoRequest.poNumber = "PO-001"

        when: "calling linkPurchaseOrder"
            controller.linkPurchaseOrder(xContextId, assignedId, linkPoRequest)

        then: "service throws exception"
            1 * documentMatchService.initiateManualLink(123L, assignedId, "PO-001") >> {
                throw new IllegalStateException("Failed to link")
            }

        and: "RuntimeException is thrown with wrapped exception"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to initiate PO link")
            ex.cause instanceof IllegalStateException
    }

    // ==================== rematch ====================

    def "rematch returns ACCEPTED when successful"() {
        given: "valid assignedId"
            def assignedId = "task-789"

        when: "calling rematch"
            def response = controller.rematch(assignedId)

        then: "service initiates auto match"
            1 * documentMatchService.initiateAutoMatch(assignedId)

        and: "response status is ACCEPTED"
            response.statusCode == HttpStatus.ACCEPTED
    }

    def "rematch throws RuntimeException when service fails"() {
        given: "valid assignedId but service fails"
            def assignedId = "task-789"

        when: "calling rematch"
            controller.rematch(assignedId)

        then: "service throws exception"
            1 * documentMatchService.initiateAutoMatch(assignedId) >> {
                throw new IllegalStateException("Failed to rematch")
            }

        and: "RuntimeException is thrown with wrapped exception"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to initiate re-match")
            ex.cause instanceof IllegalStateException
    }
}
