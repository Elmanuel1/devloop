package com.tosspaper.precon

import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for {@link DocumentContentReader}.
 *
 * <p>AWS SDK's {@link software.amazon.awssdk.core.ResponseInputStream} is a final class
 * that Spock cannot mock. The failure paths (SDK exception, null return) are exercised
 * directly. The S3 key and bucket routing is verified by inspecting the {@link GetObjectRequest}
 * captured from the mock {@link S3Client}.
 */
class DocumentContentReaderSpec extends Specification {

    S3Client s3Client = Mock()
    TenderFileProperties fileProperties = Mock()

    @Subject
    DocumentContentReader reader

    static final String EXTRACTION_ID = "ext-001"
    static final String DOCUMENT_ID   = "doc-001"
    static final String S3_KEY        = "tenders/1/tender-1/doc-001/file.pdf"
    static final String BUCKET        = "tosspaper-docs"

    def setup() {
        reader = new DocumentContentReader(s3Client, fileProperties)
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    def "TC-CR-01: read returns null when S3Client throws SdkException"() {
        given:
            fileProperties.getUploadBucket() >> BUCKET
            s3Client.getObject(_ as GetObjectRequest) >> {
                throw SdkException.builder().message("S3 service unavailable").build()
            }

        when:
            def result = reader.read(EXTRACTION_ID, DOCUMENT_ID, S3_KEY)

        then:
            result == null
    }

    def "TC-CR-02: read passes the bucket from TenderFileProperties to the S3 request"() {
        given: "a different bucket name from properties"
            fileProperties.getUploadBucket() >> "custom-bucket"
            def capturedRequests = []
            s3Client.getObject(_ as GetObjectRequest) >> { GetObjectRequest req ->
                capturedRequests << req
                throw SdkException.builder().message("abort after capture").build()
            }

        when:
            reader.read(EXTRACTION_ID, DOCUMENT_ID, S3_KEY)

        then:
            capturedRequests.size() == 1
            capturedRequests[0].bucket() == "custom-bucket"
    }

    def "TC-CR-03: read passes the s3Key verbatim to the S3 request"() {
        given:
            fileProperties.getUploadBucket() >> BUCKET
            def capturedRequests = []
            s3Client.getObject(_ as GetObjectRequest) >> { GetObjectRequest req ->
                capturedRequests << req
                throw SdkException.builder().message("abort after capture").build()
            }

        when:
            reader.read(EXTRACTION_ID, DOCUMENT_ID, S3_KEY)

        then:
            capturedRequests.size() == 1
            capturedRequests[0].key() == S3_KEY
    }

    def "TC-CR-04: read returns null for multiple documents when S3 consistently fails"() {
        given:
            fileProperties.getUploadBucket() >> BUCKET
            s3Client.getObject(_ as GetObjectRequest) >> {
                throw SdkException.builder().message("not found").build()
            }

        expect:
            reader.read("ext-A", docId, "some/key.pdf") == null

        where:
            docId << ["doc-1", "doc-2", "doc-abc-123"]
    }

    def "TC-CR-05: read does not propagate SdkException to caller — swallows and returns null"() {
        given:
            fileProperties.getUploadBucket() >> BUCKET
            s3Client.getObject(_ as GetObjectRequest) >> {
                throw SdkException.builder().message("throttled").build()
            }

        when:
            def result = reader.read(EXTRACTION_ID, DOCUMENT_ID, S3_KEY)

        then:
            noExceptionThrown()
            result == null
    }
}
