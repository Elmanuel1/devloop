package com.tosspaper.integrations.quickbooks.webhook

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.integrations.provider.IntegrationEntityType
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class QBOCloudEventSpec extends Specification {

    ObjectMapper objectMapper

    def setup() {
        objectMapper = new ObjectMapper()
        objectMapper.registerModule(new JavaTimeModule())
    }

    def "should deserialize CloudEvents from JSON file"() {
        given:
        def json = getClass().getResourceAsStream('/qbo-events.json').text

        when:
        List<QBOCloudEvent> events = objectMapper.readValue(json, new TypeReference<List<QBOCloudEvent>>() {})

        then:
        events.size() == 15
        events.every { it.id != null }
        events.every { it.type != null }
        events.every { it.entityId != null }
        events.every { it.accountId != null }
    }

    def "should parse entity type and operation from type field"() {
        given:
        def json = getClass().getResourceAsStream('/qbo-events.json').text
        List<QBOCloudEvent> events = objectMapper.readValue(json, new TypeReference<List<QBOCloudEvent>>() {})

        when:
        def firstEvent = events[0]

        then:
        firstEvent.entityType == IntegrationEntityType.PURCHASE_ORDER
        firstEvent.operation == QBOEventOperation.DELETED
        firstEvent.entityId == "1234"
        firstEvent.accountId == "310687"
    }

    def "should parse time as OffsetDateTime"() {
        given:
        def json = getClass().getResourceAsStream('/qbo-events.json').text
        List<QBOCloudEvent> events = objectMapper.readValue(json, new TypeReference<List<QBOCloudEvent>>() {})

        when:
        def event = events[0]

        then:
        event.time != null
        event.time instanceof OffsetDateTime
    }

    @Unroll
    def "should parse type '#type' as entityType=#expectedEntityType, operation=#expectedOperation"() {
        given:
        def event = new QBOCloudEvent()
        event.type = type

        expect:
        event.entityType == expectedEntityType
        event.operation == expectedOperation

        where:
        type                              | expectedEntityType                   | expectedOperation
        "qbo.purchaseorder.created.v1"    | IntegrationEntityType.PURCHASE_ORDER | QBOEventOperation.CREATED
        "qbo.purchaseorder.updated.v1"    | IntegrationEntityType.PURCHASE_ORDER | QBOEventOperation.UPDATED
        "qbo.purchaseorder.deleted.v1"    | IntegrationEntityType.PURCHASE_ORDER | QBOEventOperation.DELETED
        "qbo.bill.created.v1"             | IntegrationEntityType.BILL           | QBOEventOperation.CREATED
        "qbo.vendor.updated.v1"           | IntegrationEntityType.VENDOR         | QBOEventOperation.UPDATED
        "qbo.account.deleted.v1"          | IntegrationEntityType.ACCOUNT        | QBOEventOperation.DELETED
        "qbo.term.created.v1"             | IntegrationEntityType.PAYMENT_TERM   | QBOEventOperation.CREATED
    }

    @Unroll
    def "should return null for invalid type '#type'"() {
        given:
        def event = new QBOCloudEvent()
        event.type = type

        expect:
        event.entityType == null || event.operation == null

        where:
        type << [
            null,
            "",
            "invalid",
            "qbo.unknown.created.v1",
            "qbo.purchaseorder"
        ]
    }

    def "should still parse entity type for non-qbo prefix"() {
        given:
        def event = new QBOCloudEvent()
        event.type = "other.purchaseorder.created.v1"

        expect: "entity type and operation are parsed from position, not prefix"
        event.entityType == IntegrationEntityType.PURCHASE_ORDER
        event.operation == QBOEventOperation.CREATED
    }

    def "should deserialize single CloudEvent"() {
        given:
        def json = '''
        {
            "specversion": "1.0",
            "id": "test-event-123",
            "source": "intuit.test",
            "type": "qbo.purchaseorder.updated.v1",
            "time": "2025-12-16T16:03:24Z",
            "intuitentityid": "45",
            "intuitaccountid": "9341455841580195",
            "data": {}
        }
        '''

        when:
        def event = objectMapper.readValue(json, QBOCloudEvent)

        then:
        event.id == "test-event-123"
        event.specversion == "1.0"
        event.source == "intuit.test"
        event.type == "qbo.purchaseorder.updated.v1"
        event.entityId == "45"
        event.accountId == "9341455841580195"
        event.entityType == IntegrationEntityType.PURCHASE_ORDER
        event.operation == QBOEventOperation.UPDATED
        event.time != null
        event.data != null
    }

    def "should handle missing optional fields gracefully"() {
        given:
        def json = '''
        {
            "type": "qbo.purchaseorder.created.v1",
            "intuitentityid": "123",
            "intuitaccountid": "456"
        }
        '''

        when:
        def event = objectMapper.readValue(json, QBOCloudEvent)

        then:
        event.id == null
        event.specversion == null
        event.source == null
        event.time == null
        event.data == null
        event.entityId == "123"
        event.accountId == "456"
        event.entityType == IntegrationEntityType.PURCHASE_ORDER
        event.operation == QBOEventOperation.CREATED
    }
}
