package com.tosspaper.precon

import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import spock.lang.Specification
import spock.lang.Subject

class DocumentContentReaderSpec extends Specification {

    S3Client s3Client = Mock()
    TenderFileProperties fileProperties = Mock()

    @Subject
    DocumentContentReader reader

    static final String S3_KEY = "tenders/1/tender-1/doc-001/file.pdf"
    static final String BUCKET = "tosspaper-docs"

    def setup() {
        reader = new DocumentContentReader(s3Client, fileProperties)
    }

    def "TC-CR-01: read propagates SdkException via @SneakyThrows"() {
        given:
            fileProperties.getUploadBucket() >> BUCKET
            s3Client.getObject(_ as GetObjectRequest) >> {
                throw SdkException.builder().message("S3 unavailable").build()
            }

        when:
            reader.read(S3_KEY)

        then:
            thrown(SdkException)
    }

    def "TC-CR-02: read passes the bucket from TenderFileProperties to the S3 request"() {
        given:
            fileProperties.getUploadBucket() >> "custom-bucket"
            def capturedRequests = []
            s3Client.getObject(_ as GetObjectRequest) >> { GetObjectRequest req ->
                capturedRequests << req
                throw SdkException.builder().message("abort after capture").build()
            }

        when:
            try { reader.read(S3_KEY) } catch (ignored) {}

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
            try { reader.read(S3_KEY) } catch (ignored) {}

        then:
            capturedRequests.size() == 1
            capturedRequests[0].key() == S3_KEY
    }
}
