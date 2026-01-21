package com.tosspaper.delivery_notes

import com.tosspaper.generated.model.DeliveryNote
import com.tosspaper.generated.model.DeliveryNoteList
import com.tosspaper.generated.model.Pagination
import org.springframework.http.HttpStatus
import spock.lang.Specification

class DeliveryNoteControllerSpec extends Specification {

    DeliveryNoteService deliveryNoteService
    DeliveryNoteController controller

    def setup() {
        deliveryNoteService = Mock()
        controller = new DeliveryNoteController(deliveryNoteService)
    }

    // ==================== getDeliveryNotes ====================

    def "getDeliveryNotes returns OK with delivery note list"() {
        given: "valid context and query parameters"
            def xContextId = "123"
            def projectId = "proj-1"
            def purchaseOrderId = "po-1"
            def poNumber = "PO-001"
            def limit = 10
            def cursor = null
            def search = "test"

            def deliveryNoteList = new DeliveryNoteList()
            deliveryNoteList.setData([createDeliveryNote("note-1"), createDeliveryNote("note-2")])
            deliveryNoteList.setPagination(new Pagination())

        when: "calling getDeliveryNotes"
            def response = controller.getDeliveryNotes(xContextId, projectId, purchaseOrderId, poNumber, limit, cursor, search)

        then: "service is called with parsed company ID and parameters"
            1 * deliveryNoteService.getDeliveryNotes(123L, projectId, purchaseOrderId, poNumber, search, limit, cursor) >> deliveryNoteList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery notes"
            with(response.body) {
                data.size() == 2
                data[0].id == "note-1"
                data[1].id == "note-2"
            }
    }

    def "getDeliveryNotes handles null optional parameters"() {
        given: "only required context parameter"
            def xContextId = "456"
            def deliveryNoteList = new DeliveryNoteList()
            deliveryNoteList.setData([])
            deliveryNoteList.setPagination(new Pagination())

        when: "calling getDeliveryNotes with null optional parameters"
            def response = controller.getDeliveryNotes(xContextId, null, null, null, null, null, null)

        then: "service is called with null parameters"
            1 * deliveryNoteService.getDeliveryNotes(456L, null, null, null, null, null, null) >> deliveryNoteList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "getDeliveryNotes returns empty list when no notes found"() {
        given: "a company with no delivery notes"
            def xContextId = "789"
            def deliveryNoteList = new DeliveryNoteList()
            deliveryNoteList.setData([])
            deliveryNoteList.setPagination(new Pagination())

        when: "calling getDeliveryNotes"
            def response = controller.getDeliveryNotes(xContextId, null, null, null, null, null, null)

        then: "service returns empty list"
            1 * deliveryNoteService.getDeliveryNotes(789L, null, null, null, null, null, null) >> deliveryNoteList

        and: "response contains empty list"
            response.statusCode == HttpStatus.OK
            response.body.data.isEmpty()
    }

    // ==================== getDeliveryNoteById ====================

    def "getDeliveryNoteById returns OK with delivery note"() {
        given: "valid context and note ID"
            def xContextId = "123"
            def noteId = "note-123"
            def deliveryNote = createDeliveryNote(noteId)

        when: "calling getDeliveryNoteById"
            def response = controller.getDeliveryNoteById(xContextId, noteId)

        then: "service is called with parsed company ID and note ID"
            1 * deliveryNoteService.getDeliveryNoteById(123L, noteId) >> deliveryNote

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery note"
            response.body.id == noteId
    }

    def "getDeliveryNoteById propagates exception from service"() {
        given: "a note that does not exist"
            def xContextId = "123"
            def noteId = "non-existent"

        when: "calling getDeliveryNoteById"
            controller.getDeliveryNoteById(xContextId, noteId)

        then: "service throws exception"
            1 * deliveryNoteService.getDeliveryNoteById(123L, noteId) >> {
                throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Delivery note not found")
            }

        and: "exception is propagated"
            thrown(org.springframework.web.server.ResponseStatusException)
    }

    // ==================== Helper Methods ====================

    private static DeliveryNote createDeliveryNote(String id) {
        def note = new DeliveryNote()
        note.id = id
        return note
    }
}
