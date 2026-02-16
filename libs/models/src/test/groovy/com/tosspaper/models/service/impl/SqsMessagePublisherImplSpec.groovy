package com.tosspaper.models.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.models.properties.SqsProperties
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import software.amazon.awssdk.services.sqs.model.SqsException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for SqsMessagePublisherImpl.
 * Verifies SQS message publishing with trace context propagation.
 */
class SqsMessagePublisherImplSpec extends Specification {

    SqsClient sqsClient = Mock()
    SqsProperties sqsProperties = new SqsProperties()
    Tracer tracer = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    SqsMessagePublisherImpl publisher

    def setup() {
        sqsProperties.queuePrefix = "test-env"
        sqsProperties.region = "us-east-1"
        publisher = new SqsMessagePublisherImpl(sqsClient, sqsProperties, tracer, objectMapper)
    }

    // ==================== publish Tests ====================

    def "publish should send message to SQS queue"() {
        given:
        def queueName = "email-uploads"
        def message = ["key": "value", "action": "process"]

        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-email-uploads"

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, message)

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >> { args ->
            def consumer = args[0] as java.util.function.Consumer
            def builder = GetQueueUrlRequest.builder()
            consumer.accept(builder)
            def req = builder.build()
            assert req.queueName() == "test-env-email-uploads"
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()
        }

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            assert req.queueUrl() == queueUrl
            assert req.messageBody().contains("\"key\":\"value\"")
            assert req.messageBody().contains("\"action\":\"process\"")
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    def "publish should cache queue URL for subsequent calls"() {
        given:
        def queueName = "cached-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-cached-queue"

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, ["msg": "1"])
        publisher.publish(queueName, ["msg": "2"])

        then: "getQueueUrl should only be called once due to caching"
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        and: "two messages should be sent"
        2 * sqsClient.sendMessage(_ as SendMessageRequest) >> SendMessageResponse.builder().messageId("msg").build()
    }

    def "publish should include traceparent header when span is available"() {
        given:
        def queueName = "traced-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-traced-queue"

        def traceContext = Mock(TraceContext)
        traceContext.traceId() >> "0af7651916cd43dd8448eb211c80319c"
        traceContext.spanId() >> "b7ad6b7169203331"

        def currentSpan = Mock(Span)
        currentSpan.context() >> traceContext

        tracer.currentSpan() >> currentSpan

        when:
        publisher.publish(queueName, ["test": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            def attrs = req.messageAttributes()
            assert attrs.containsKey("traceparent")
            def traceparent = attrs.get("traceparent").stringValue()
            assert traceparent.startsWith("00-")
            assert traceparent.contains("0af7651916cd43dd8448eb211c80319c")
            assert traceparent.contains("b7ad6b7169203331")
            assert traceparent.endsWith("-01")
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    def "publish should handle null trace context gracefully"() {
        given:
        def queueName = "no-trace-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-no-trace-queue"

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            // Should not have traceparent when no span
            def attrs = req.messageAttributes()
            assert !attrs.containsKey("traceparent") || attrs.isEmpty()
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    def "publish should handle partial trace context"() {
        given:
        def queueName = "partial-trace-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-partial-trace-queue"

        def traceContext = Mock(TraceContext)
        traceContext.traceId() >> null
        traceContext.spanId() >> "abc123"

        def currentSpan = Mock(Span)
        currentSpan.context() >> traceContext

        tracer.currentSpan() >> currentSpan

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            // Should not include traceparent when traceId is null
            def attrs = req.messageAttributes()
            assert !attrs.containsKey("traceparent") || attrs.isEmpty()
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    // ==================== Error Handling Tests ====================

    def "publish should throw RuntimeException when SQS sendMessage fails"() {
        given:
        def queueName = "fail-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-fail-queue"

        tracer.currentSpan() >> null

        def sqsException = SqsException.builder()
            .message("Service unavailable")
            .build()

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { throw sqsException }

        and:
        def ex = thrown(RuntimeException)
        ex.message.contains("Failed to publish to SQS queue")
        ex.message.contains("fail-queue")
    }

    def "publish should throw RuntimeException when getQueueUrl fails"() {
        given:
        def queueName = "nonexistent-queue"

        tracer.currentSpan() >> null

        def sqsException = SqsException.builder()
            .message("Queue does not exist")
            .build()

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >> { throw sqsException }

        and:
        def ex = thrown(RuntimeException)
        ex.message.contains("Failed to publish to SQS queue")
    }

    // ==================== Queue Name Tests ====================

    def "publish should use correct queue prefix"() {
        given:
        def queueName = "my-queue"
        sqsProperties.queuePrefix = "prod-tosspaper"

        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/prod-tosspaper-my-queue"

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >> { args ->
            def consumer = args[0] as java.util.function.Consumer
            def builder = GetQueueUrlRequest.builder()
            consumer.accept(builder)
            def req = builder.build()
            assert req.queueName() == "prod-tosspaper-my-queue"
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()
        }

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> SendMessageResponse.builder().messageId("msg").build()
    }

    // ==================== Message Serialization Tests ====================

    def "publish should serialize message to JSON"() {
        given:
        def queueName = "json-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-json-queue"
        def message = [
            "file_id": "file-123",
            "action": "extract",
            "status": "pending"
        ]

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, message)

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            def body = req.messageBody()
            // Verify it's valid JSON that can be parsed
            def parsed = new ObjectMapper().readValue(body, Map)
            assert parsed["file_id"] == "file-123"
            assert parsed["action"] == "extract"
            assert parsed["status"] == "pending"
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    def "publish should handle empty message map"() {
        given:
        def queueName = "empty-msg-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-empty-msg-queue"

        tracer.currentSpan() >> null

        when:
        publisher.publish(queueName, [:])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            assert req.messageBody() == "{}"
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    // ==================== Trace ID Padding Tests ====================

    def "should pad short trace IDs to correct length"() {
        given:
        def queueName = "short-trace-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-short-trace-queue"

        def traceContext = Mock(TraceContext)
        traceContext.traceId() >> "abc123"  // Short trace ID
        traceContext.spanId() >> "def456"   // Short span ID

        def currentSpan = Mock(Span)
        currentSpan.context() >> traceContext

        tracer.currentSpan() >> currentSpan

        when:
        publisher.publish(queueName, ["test": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            def traceparent = req.messageAttributes().get("traceparent").stringValue()
            def parts = traceparent.split("-")
            assert parts[1].length() == 32  // Trace ID should be 32 chars
            assert parts[2].length() == 16  // Span ID should be 16 chars
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }

    // ==================== Message Attributes Tests ====================

    def "message attributes should have correct data type"() {
        given:
        def queueName = "attr-type-queue"
        def queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-env-attr-type-queue"

        def traceContext = Mock(TraceContext)
        traceContext.traceId() >> "0af7651916cd43dd8448eb211c80319c"
        traceContext.spanId() >> "b7ad6b7169203331"

        def currentSpan = Mock(Span)
        currentSpan.context() >> traceContext

        tracer.currentSpan() >> currentSpan

        when:
        publisher.publish(queueName, ["key": "value"])

        then:
        1 * sqsClient.getQueueUrl(_ as java.util.function.Consumer) >>
            GetQueueUrlResponse.builder().queueUrl(queueUrl).build()

        then:
        1 * sqsClient.sendMessage(_ as SendMessageRequest) >> { SendMessageRequest req ->
            def traceparentAttr = req.messageAttributes().get("traceparent")
            assert traceparentAttr.dataType() == "String"
            SendMessageResponse.builder().messageId("msg-123").build()
        }
    }
}
