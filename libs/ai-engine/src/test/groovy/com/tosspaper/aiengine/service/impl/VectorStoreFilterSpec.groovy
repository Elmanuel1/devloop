package com.tosspaper.aiengine.service.impl

import spock.lang.Specification

class VectorStoreFilterSpec extends Specification {

    def "toString should generate filter with all fields"() {
        given:
            def filter = VectorStoreFilter.builder()
                .assignedEmail("user@example.com")
                .purchaseOrderId("po-123")
                .partType("line_item")
                .build()

        expect:
            filter.toString() == "assignedEmail == 'user@example.com' AND purchaseOrderId == 'po-123' AND partType == 'line_item'"
    }

    def "toString should generate filter with only assignedEmail"() {
        given:
            def filter = VectorStoreFilter.builder()
                .assignedEmail("user@example.com")
                .build()

        expect:
            filter.toString() == "assignedEmail == 'user@example.com'"
    }

    def "toString should generate filter with only purchaseOrderId"() {
        given:
            def filter = VectorStoreFilter.builder()
                .purchaseOrderId("po-456")
                .build()

        expect:
            filter.toString() == "purchaseOrderId == 'po-456'"
    }

    def "toString should generate filter with only partType"() {
        given:
            def filter = VectorStoreFilter.builder()
                .partType("vendor_contact")
                .build()

        expect:
            filter.toString() == "partType == 'vendor_contact'"
    }

    def "toString should generate empty string when no fields set"() {
        given:
            def filter = VectorStoreFilter.builder().build()

        expect:
            filter.toString() == ""
    }

    def "toString should generate filter with assignedEmail and partType"() {
        given:
            def filter = VectorStoreFilter.builder()
                .assignedEmail("admin@test.com")
                .partType("ship_to_contact")
                .build()

        expect:
            filter.toString() == "assignedEmail == 'admin@test.com' AND partType == 'ship_to_contact'"
    }

    def "toString should generate filter with purchaseOrderId and partType"() {
        given:
            def filter = VectorStoreFilter.builder()
                .purchaseOrderId("po-789")
                .partType("po_info")
                .build()

        expect:
            filter.toString() == "purchaseOrderId == 'po-789' AND partType == 'po_info'"
    }

    def "getter methods should return correct values"() {
        given:
            def filter = VectorStoreFilter.builder()
                .assignedEmail("test@mail.com")
                .purchaseOrderId("po-100")
                .partType("line_item")
                .build()

        expect:
            filter.getAssignedEmail() == "test@mail.com"
            filter.getPurchaseOrderId() == "po-100"
            filter.getPartType() == "line_item"
    }
}
