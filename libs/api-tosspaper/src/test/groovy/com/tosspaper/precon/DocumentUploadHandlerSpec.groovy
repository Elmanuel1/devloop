package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

class DocumentUploadHandlerSpec extends Specification {

    DocumentUploadProcessor processor = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    DocumentUploadHandler handler

    def setup() {
        handler = new DocumentUploadHandler(processor, objectMapper)
    }

    def "should parse S3 event and delegate to processor"() {
        given:
            def message = [
                "Records": [[
                    "eventVersion": "2.1",
                    "eventSource": "aws:s3",
                    "eventName": "ObjectCreated:Put",
                    "s3": [
                        "bucket": ["name": "test-bucket"],
                        "object": ["key": "tender-uploads/1/tid-123/did-456/document.pdf", "size": 12345]
                    ]
                ]]
            ]

        when:
            handler.handle(message)

        then:
            1 * processor.processUpload("test-bucket", "tender-uploads/1/tid-123/did-456/document.pdf", 12345)
    }

    def "should handle multiple records in S3 event"() {
        given:
            def message = [
                "Records": [
                    [
                        "s3": [
                            "bucket": ["name": "bucket-1"],
                            "object": ["key": "tender-uploads/1/t1/d1/file1.pdf", "size": 100]
                        ]
                    ],
                    [
                        "s3": [
                            "bucket": ["name": "bucket-1"],
                            "object": ["key": "tender-uploads/1/t1/d2/file2.png", "size": 200]
                        ]
                    ]
                ]
            ]

        when:
            handler.handle(message)

        then:
            1 * processor.processUpload("bucket-1", "tender-uploads/1/t1/d1/file1.pdf", 100)
            1 * processor.processUpload("bucket-1", "tender-uploads/1/t1/d2/file2.png", 200)
    }

    def "should handle empty records array"() {
        given:
            def message = ["Records": []]

        when:
            handler.handle(message)

        then:
            0 * processor.processUpload(_, _, _)
            noExceptionThrown()
    }

    def "should handle S3 event with URL-encoded key"() {
        given:
            def message = [
                "Records": [[
                    "s3": [
                        "bucket": ["name": "test-bucket"],
                        "object": ["key": "tender-uploads/1/tid-123/did-456/my+document.pdf", "size": 5000]
                    ]
                ]]
            ]

        when:
            handler.handle(message)

        then:
            1 * processor.processUpload("test-bucket", "tender-uploads/1/tid-123/did-456/my document.pdf", 5000)
    }

    def "should return correct queue name"() {
        expect:
            handler.getQueueName() == "tender-upload-events"
    }
}
