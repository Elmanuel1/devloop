package com.tosspaper.delivery_notes

import com.tosspaper.generated.model.DeliveryNote
import com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import spock.lang.Specification

import java.time.OffsetDateTime

class DeliveryNoteServiceSpec extends Specification {

    DeliveryNoteRepository deliveryNoteRepository
    DeliveryNoteMapper deliveryNoteMapper
    DeliveryNoteServiceImpl service

    def setup() {
        deliveryNoteRepository = Mock()
        deliveryNoteMapper = Mock()
        service = new DeliveryNoteServiceImpl(deliveryNoteRepository, deliveryNoteMapper)
    }

    // ==================== getDeliveryNotes ====================

    def "getDeliveryNotes returns paginated list with default limit"() {
        given: "a company ID"
            def companyId = 1L
            def records = [createRecord("note-1", companyId), createRecord("note-2", companyId)]
            def notes = [createDeliveryNote("note-1"), createDeliveryNote("note-2")]

        when: "fetching delivery notes without limit"
            def result = service.getDeliveryNotes(companyId, null, null, null, null, null, null)

        then: "repository is called with default page size of 20"
            1 * deliveryNoteRepository.findDeliveryNotes(companyId, _ as DeliveryNoteQuery) >> { Long cId, DeliveryNoteQuery q ->
                assert q.pageSize == 20
                return records
            }

        and: "records are mapped"
            1 * deliveryNoteMapper.toDtoList(records) >> notes

        and: "result contains delivery notes"
            with(result) {
                data.size() == 2
                data[0].id == "note-1"
                data[1].id == "note-2"
            }
    }

    def "getDeliveryNotes uses provided limit"() {
        given: "a specific limit"
            def companyId = 1L
            def limit = 5

        when: "fetching delivery notes with limit"
            def result = service.getDeliveryNotes(companyId, null, null, null, null, limit, null)

        then: "repository is called with provided limit"
            1 * deliveryNoteRepository.findDeliveryNotes(companyId, _ as DeliveryNoteQuery) >> { Long cId, DeliveryNoteQuery q ->
                assert q.pageSize == 5
                return []
            }
            1 * deliveryNoteMapper.toDtoList([]) >> []

        and: "result is returned"
            result.data.isEmpty()
    }

    def "getDeliveryNotes generates next cursor when results equal page size"() {
        given: "results that fill the page"
            def companyId = 1L
            def limit = 2
            def records = [createRecord("note-1", companyId), createRecord("note-2", companyId)]

        when: "fetching delivery notes"
            def result = service.getDeliveryNotes(companyId, null, null, null, null, limit, null)

        then: "repository returns exactly limit records"
            1 * deliveryNoteRepository.findDeliveryNotes(companyId, _) >> records
            1 * deliveryNoteMapper.toDtoList(records) >> [createDeliveryNote("note-1"), createDeliveryNote("note-2")]

        and: "next cursor is generated"
            result.pagination.cursor != null
    }

    def "getDeliveryNotes returns null cursor when results less than page size"() {
        given: "results that don't fill the page"
            def companyId = 1L
            def limit = 10
            def records = [createRecord("note-1", companyId)]

        when: "fetching delivery notes"
            def result = service.getDeliveryNotes(companyId, null, null, null, null, limit, null)

        then: "repository returns less than limit"
            1 * deliveryNoteRepository.findDeliveryNotes(companyId, _) >> records
            1 * deliveryNoteMapper.toDtoList(records) >> [createDeliveryNote("note-1")]

        and: "no next cursor"
            result.pagination.cursor == null
    }

    def "getDeliveryNotes passes filter parameters to query"() {
        given: "filter parameters"
            def companyId = 1L
            def projectId = "proj-1"
            def purchaseOrderId = "po-1"
            def poNumber = "PO-001"
            def search = "test"

        when: "fetching delivery notes with filters"
            service.getDeliveryNotes(companyId, projectId, purchaseOrderId, poNumber, search, 20, null)

        then: "repository is called with all filters"
            1 * deliveryNoteRepository.findDeliveryNotes(companyId, _ as DeliveryNoteQuery) >> { Long cId, DeliveryNoteQuery q ->
                assert q.projectId == projectId
                assert q.purchaseOrderId == purchaseOrderId
                assert q.poNumber == poNumber
                assert q.search == search
                return []
            }
            1 * deliveryNoteMapper.toDtoList([]) >> []
    }

    def "getDeliveryNotes throws IllegalArgumentException for invalid cursor format"() {
        given: "an invalid cursor"
            def companyId = 1L
            def invalidCursor = "not-a-valid-cursor"

        when: "fetching delivery notes with invalid cursor"
            service.getDeliveryNotes(companyId, null, null, null, null, 20, invalidCursor)

        then: "IllegalArgumentException is thrown"
            def ex = thrown(IllegalArgumentException)
            ex.message.contains("Invalid cursor format")
    }

    // ==================== getDeliveryNoteById ====================

    def "getDeliveryNoteById returns delivery note when found and company matches"() {
        given: "an existing delivery note"
            def companyId = 1L
            def noteId = "note-123"
            def record = createRecord(noteId, companyId)
            def note = createDeliveryNote(noteId)

        when: "fetching delivery note by ID"
            def result = service.getDeliveryNoteById(companyId, noteId)

        then: "repository returns record"
            1 * deliveryNoteRepository.findById(noteId) >> record

        and: "record is mapped"
            1 * deliveryNoteMapper.toDto(record) >> note

        and: "result has correct ID"
            with(result) {
                id == noteId
            }
    }

    def "getDeliveryNoteById throws 404 when delivery note not found"() {
        given: "non-existent delivery note ID"
            def companyId = 1L
            def noteId = "non-existent"

        when: "fetching delivery note by ID"
            service.getDeliveryNoteById(companyId, noteId)

        then: "repository returns null"
            1 * deliveryNoteRepository.findById(noteId) >> null

        and: "ResponseStatusException with 404 is thrown"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Delivery note not found")
    }

    def "getDeliveryNoteById throws 404 when company does not match"() {
        given: "a delivery note from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def noteId = "note-123"
            def record = createRecord(noteId, differentCompanyId)

        when: "fetching delivery note by ID"
            service.getDeliveryNoteById(companyId, noteId)

        then: "repository returns record from different company"
            1 * deliveryNoteRepository.findById(noteId) >> record

        and: "ResponseStatusException with 404 is thrown"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Delivery note not found")

        and: "no mapping occurs"
            0 * deliveryNoteMapper.toDto(_)
    }

    // ==================== Helper Methods ====================

    private static DeliveryNotesRecord createRecord(String id, Long companyId) {
        def record = new DeliveryNotesRecord()
        record.id = id
        record.companyId = companyId
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private static DeliveryNote createDeliveryNote(String id) {
        def note = new DeliveryNote()
        note.id = id
        return note
    }
}
