package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.validation.MagicByteValidation
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import spock.lang.Specification

class DocumentUploadProcessorSpec extends Specification {

    TenderDocumentRepository documentRepository = Mock()
    S3Client s3Client = Mock()
    MagicByteValidation magicByteValidation = new MagicByteValidation()

    DocumentUploadProcessor processor

    String bucket = "test-bucket"
    String s3Key = "tender-uploads/1/tid-123/did-456/document.pdf"

    def setup() {
        processor = new DocumentUploadProcessor(documentRepository, s3Client, magicByteValidation)
    }

    private TenderDocumentsRecord mockDocumentRecord(String docId, String contentType) {
        def record = Mock(TenderDocumentsRecord)
        record.getId() >> docId
        record.getContentType() >> contentType
        record.getFileName() >> "document.pdf"
        return record
    }

    private ResponseInputStream<GetObjectResponse> mockS3Response(byte[] data) {
        def response = GetObjectResponse.builder().build()
        def inputStream = AbortableInputStream.create(new ByteArrayInputStream(data))
        return new ResponseInputStream<>(response, inputStream)
    }

    def "should process valid PDF upload"() {
        given:
            def docId = "did-456"
            byte[] pdfHeader = [0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34] as byte[]
            def document = mockDocumentRecord(docId, "application/pdf")

            documentRepository.findById(docId) >> Optional.of(document)
            s3Client.getObject(_ as GetObjectRequest) >> mockS3Response(pdfHeader)

        when:
            processor.processUpload(bucket, s3Key, 1024)

        then:
            1 * documentRepository.updateStatusToProcessing(docId)
            1 * documentRepository.updateStatusToReady(docId)
            0 * documentRepository.updateStatusToFailed(_, _)
    }

    def "should process valid PNG upload"() {
        given:
            def docId = "did-456"
            def pngKey = "tender-uploads/1/tid-123/did-456/image.png"
            byte[] pngHeader = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]
            def document = Mock(TenderDocumentsRecord)
            document.getId() >> docId
            document.getContentType() >> "image/png"
            document.getFileName() >> "image.png"

            documentRepository.findById(docId) >> Optional.of(document)
            s3Client.getObject(_ as GetObjectRequest) >> mockS3Response(pngHeader)

        when:
            processor.processUpload(bucket, pngKey, 2048)

        then:
            1 * documentRepository.updateStatusToProcessing(docId)
            1 * documentRepository.updateStatusToReady(docId)
            0 * documentRepository.updateStatusToFailed(_, _)
    }

    def "should process valid JPEG upload"() {
        given:
            def docId = "did-456"
            def jpegKey = "tender-uploads/1/tid-123/did-456/photo.jpg"
            byte[] jpegHeader = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46] as byte[]
            def document = Mock(TenderDocumentsRecord)
            document.getId() >> docId
            document.getContentType() >> "image/jpeg"
            document.getFileName() >> "photo.jpg"

            documentRepository.findById(docId) >> Optional.of(document)
            s3Client.getObject(_ as GetObjectRequest) >> mockS3Response(jpegHeader)

        when:
            processor.processUpload(bucket, jpegKey, 4096)

        then:
            1 * documentRepository.updateStatusToProcessing(docId)
            1 * documentRepository.updateStatusToReady(docId)
            0 * documentRepository.updateStatusToFailed(_, _)
    }

    def "should fail for invalid magic bytes"() {
        given:
            def docId = "did-456"
            byte[] pngHeader = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]
            def document = mockDocumentRecord(docId, "application/pdf")

            documentRepository.findById(docId) >> Optional.of(document)
            s3Client.getObject(_ as GetObjectRequest) >> mockS3Response(pngHeader)

        when:
            processor.processUpload(bucket, s3Key, 1024)

        then:
            1 * documentRepository.updateStatusToProcessing(docId)
            0 * documentRepository.updateStatusToReady(_)
            1 * documentRepository.updateStatusToFailed(docId, { it.contains("magic bytes do not match") })
    }

    def "should fail when S3 object not found"() {
        given:
            def docId = "did-456"
            def document = mockDocumentRecord(docId, "application/pdf")

            documentRepository.findById(docId) >> Optional.of(document)
            s3Client.getObject(_ as GetObjectRequest) >> { throw NoSuchKeyException.builder().message("Not found").build() }

        when:
            processor.processUpload(bucket, s3Key, 1024)

        then:
            1 * documentRepository.updateStatusToProcessing(docId)
            0 * documentRepository.updateStatusToReady(_)
            1 * documentRepository.updateStatusToFailed(docId, { it.contains("S3 object not found") })
    }

    def "should skip when document record not found"() {
        given:
            documentRepository.findById("did-456") >> Optional.empty()

        when:
            processor.processUpload(bucket, s3Key, 1024)

        then:
            0 * documentRepository.updateStatusToProcessing(_)
            0 * documentRepository.updateStatusToReady(_)
            0 * documentRepository.updateStatusToFailed(_, _)
    }

    def "should parse S3 key correctly"() {
        given:
            def key = "tender-uploads/1/tid/did/file.pdf"

        when:
            def metadata = processor.parseS3Key(key)

        then:
            metadata != null
            metadata.companyId == "1"
            metadata.tenderId == "tid"
            metadata.documentId == "did"
            metadata.fileName == "file.pdf"
    }

    def "should return null for invalid S3 key"() {
        when:
            def metadata = processor.parseS3Key("invalid/key")

        then:
            metadata == null
    }

    def "should return null for null S3 key"() {
        when:
            def metadata = processor.parseS3Key(null)

        then:
            metadata == null
    }

    def "should return null for blank S3 key"() {
        when:
            def metadata = processor.parseS3Key("  ")

        then:
            metadata == null
    }

    def "should skip processing for invalid S3 key format"() {
        given:
            def invalidKey = "some-other-prefix/file.pdf"

        when:
            processor.processUpload(bucket, invalidKey, 1024)

        then:
            0 * documentRepository.findById(_)
            0 * documentRepository.updateStatusToProcessing(_)
    }
}
