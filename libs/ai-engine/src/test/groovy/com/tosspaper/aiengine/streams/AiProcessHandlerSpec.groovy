package com.tosspaper.aiengine.streams

import com.tosspaper.aiengine.service.ExtractionService
import spock.lang.Specification
import spock.lang.Subject

class AiProcessHandlerSpec extends Specification {

    ExtractionService extractionService = Mock()

    @Subject
    AiProcessHandler handler = new AiProcessHandler(extractionService)

    def "getQueueName should return correct queue name"() {
        expect:
            handler.getQueueName() == "ai-process"
    }

    def "handle should call extraction service with correct parameters"() {
        given: "a valid message"
            def message = [
                "assignedId": "attach-123",
                "storageUrl": "s3://bucket/file.pdf"
            ]

        when: "handling the message"
            handler.handle(message)

        then: "extraction service is called"
            1 * extractionService.extract("attach-123", "s3://bucket/file.pdf")
    }

    def "handle should throw when assignedId is null"() {
        given: "a message without assignedId"
            def message = [
                "storageUrl": "s3://bucket/file.pdf"
            ]

        when: "handling the message"
            handler.handle(message)

        then: "exception is thrown"
            thrown(IllegalArgumentException)
    }

    def "handle should throw when storageUrl is null"() {
        given: "a message without storageUrl"
            def message = [
                "assignedId": "attach-123"
            ]

        when: "handling the message"
            handler.handle(message)

        then: "exception is thrown"
            thrown(IllegalArgumentException)
    }

    def "handle should catch extraction service exceptions"() {
        given: "a valid message"
            def message = [
                "assignedId": "attach-123",
                "storageUrl": "s3://bucket/file.pdf"
            ]

        and: "extraction service throws"
            extractionService.extract(_, _) >> { throw new RuntimeException("Extraction failed") }

        when: "handling the message"
            handler.handle(message)

        then: "exception is caught and no exception thrown to caller"
            noExceptionThrown()
    }
}
