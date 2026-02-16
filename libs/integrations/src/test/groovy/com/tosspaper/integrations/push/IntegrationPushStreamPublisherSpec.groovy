package com.tosspaper.integrations.push

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.messaging.MessagePublisher
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for IntegrationPushStreamPublisher.
 * Tests event serialization and publishing to Redis stream.
 */
class IntegrationPushStreamPublisherSpec extends Specification {

    MessagePublisher messagePublisher = Mock()
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    IntegrationPushStreamPublisher publisher = new IntegrationPushStreamPublisher(
        messagePublisher,
        objectMapper
    )

    def "publish should serialize and publish event to stream"() {
        given:
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            '{"id":"vendor-123"}',
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            assert message.containsKey("message")
            assert message.message.contains("conn-1")
            assert message.message.contains("QUICKBOOKS")
            assert message.message.contains("vendor-123")
        }
    }

    def "publish should include all event fields in serialized payload"() {
        given:
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.ITEM,
            200L,
            "conn-2",
            '{"name":"Widget"}',
            "admin@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            def payload = objectMapper.readValue(message.message, IntegrationPushEvent)
            assert payload.connectionId() == "conn-2"
            assert payload.provider() == IntegrationProvider.QUICKBOOKS
            assert payload.companyId() == 200L
            assert payload.entityType() == IntegrationEntityType.ITEM
            assert payload.payload() == '{"name":"Widget"}'
            assert payload.updatedBy() == "admin@example.com"
        }
    }

    def "publish should handle large payloads"() {
        given:
        def largePayload = '{"data":"' + ("a" * 10000) + '"}'
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            largePayload,
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            assert message.message.length() > 10000
        }
        notThrown(Exception)
    }

    def "publish should handle special characters in payload"() {
        given:
        def specialPayload = '{"name":"Test & <Company> \\"quoted\\""}'
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            specialPayload,
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            assert message.message.contains("Test & <Company>")
        }
    }

    def "publish should throw exception when serialization fails"() {
        given:
        def badObjectMapper = Mock(ObjectMapper) {
            writeValueAsString(_) >> { throw new com.fasterxml.jackson.core.JsonProcessingException("Serialization error") {} }
        }

        def badPublisher = new IntegrationPushStreamPublisher(messagePublisher, badObjectMapper)

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            '{"id":"123"}',
            "user@example.com"
        )

        when:
        badPublisher.publish(event)

        then:
        thrown(RuntimeException)
    }

    def "publish should publish to correct stream name"() {
        given:
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            '{"id":"123"}',
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _)
    }

    def "publish should handle purchase order entity type"() {
        given:
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.PURCHASE_ORDER,
            100L,
            "conn-1",
            '{"displayId":"PO-001"}',
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            assert message.message.contains("PURCHASE_ORDER")
        }
    }

    def "publish should handle bill entity type"() {
        given:
        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.BILL,
            100L,
            "conn-1",
            '{"documentNumber":"INV-001"}',
            "user@example.com"
        )

        when:
        publisher.publish(event)

        then:
        1 * messagePublisher.publish("integration-push-events", _) >> { String stream, Map<String, String> message ->
            assert message.message.contains("BILL")
        }
    }
}
