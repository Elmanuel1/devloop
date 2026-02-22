package com.tosspaper.precon

import com.tosspaper.common.BadRequestException
import com.tosspaper.common.NotFoundException
import com.tosspaper.generated.model.PresignedUrlRequest
import com.tosspaper.generated.model.TenderContentType
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.jooq.tables.records.TendersRecord
import com.tosspaper.models.properties.AwsProperties
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import spock.lang.Specification

import java.time.OffsetDateTime

class TenderDocumentServiceSpec extends Specification {

    TenderRepository tenderRepository = Mock()
    TenderDocumentRepository documentRepository = Mock()
    TenderDocumentMapper documentMapper = new TenderDocumentMapper()
    TenderDocumentValidator validator
    S3Presigner s3Presigner = Mock()
    S3Client s3Client = Mock()
    AwsProperties awsProperties

    TenderDocumentServiceImpl service

    Long companyId = 1L
    String companyIdStr = "1"
    String tenderId = UUID.randomUUID().toString()

    def setup() {
        def fileProperties = new TenderFileProperties()
        validator = new TenderDocumentValidator(fileProperties)

        awsProperties = new AwsProperties()
        awsProperties.bucket = new AwsProperties.Bucket()
        awsProperties.bucket.name = "test-bucket"
        awsProperties.bucket.region = "us-east-1"

        service = new TenderDocumentServiceImpl(
            tenderRepository, documentRepository, documentMapper,
            validator, s3Presigner, s3Client, awsProperties
        )
    }

    private TendersRecord mockTenderRecord(String id, String company) {
        def record = Mock(TendersRecord)
        record.getId() >> id
        record.getCompanyId() >> company
        return record
    }

    private TenderDocumentsRecord mockDocumentRecord(String docId, String tId, String status, String s3Key) {
        def record = Mock(TenderDocumentsRecord)
        record.getId() >> docId
        record.getTenderId() >> tId
        record.getCompanyId() >> companyIdStr
        record.getStatus() >> status
        record.getS3Key() >> s3Key
        record.getFileName() >> "doc.pdf"
        record.getContentType() >> "application/pdf"
        record.getFileSize() >> 1024L
        record.getCreatedAt() >> OffsetDateTime.now()
        record.getUpdatedAt() >> OffsetDateTime.now()
        return record
    }

    // ==================== Upload Presigned URL ====================

    def "should create document record and return presigned URL"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf", TenderContentType.APPLICATION_PDF, 1024L)
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))

            def presignedPutObject = Mock(PresignedPutObjectRequest)
            presignedPutObject.url() >> new URL("https://s3.amazonaws.com/test-bucket/key")
            s3Presigner.presignPutObject(_ as PutObjectPresignRequest) >> presignedPutObject

            def insertedRecord = Mock(TenderDocumentsRecord)
            documentRepository.insert(_, tenderId, companyIdStr, "doc.pdf", "application/pdf", 1024L, _, "uploading") >> insertedRecord

        when:
            def response = service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            response.presignedUrl != null
            response.documentId != null
            response.expiration != null
            1 * documentRepository.insert(_, tenderId, companyIdStr, "doc.pdf", "application/pdf", 1024L, _, "uploading")
    }

    def "should throw NotFoundException when tender not found for upload"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf", TenderContentType.APPLICATION_PDF, 1024L)
            tenderRepository.findById(tenderId) >> Optional.empty()

        when:
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            thrown(NotFoundException)
    }

    def "should throw ValidationException for invalid content type"() {
        given:
            def request = new PresignedUrlRequest()
            request.setFileName("doc.zip")
            request.setContentType(null) // invalid
            request.setFileSize(1024L)
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))

        when:
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            thrown(BadRequestException)
    }

    def "should throw ValidationException when file_size exceeds 200MB"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf", TenderContentType.APPLICATION_PDF, 209715201L)
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))

        when:
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            thrown(BadRequestException)
    }

    def "should throw ValidationException for double extension"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf.exe", TenderContentType.APPLICATION_PDF, 1024L)
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))

        when:
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            thrown(BadRequestException)
    }

    def "should throw ValidationException when extension does not match content_type"() {
        given:
            def request = new PresignedUrlRequest("doc.png", TenderContentType.APPLICATION_PDF, 1024L)
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))

        when:
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then:
            thrown(BadRequestException)
    }

    // ==================== List Documents ====================

    def "should return paginated list of documents"() {
        given:
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            def docId1 = UUID.randomUUID().toString()
            def docId2 = UUID.randomUUID().toString()
            def doc1 = mockDocumentRecord(docId1, tenderId, "ready", "key1")
            def doc2 = mockDocumentRecord(docId2, tenderId, "ready", "key2")
            documentRepository.findByTenderId(tenderId, null, 20, null, null) >> [doc1, doc2]

        when:
            def response = service.listDocuments(companyId, tenderId, null, 20, null, null)

        then:
            response.data.size() == 2
            response.pagination != null
    }

    def "should filter by status"() {
        given:
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            def docId1 = UUID.randomUUID().toString()
            def doc1 = mockDocumentRecord(docId1, tenderId, "ready", "key1")

        when:
            def response = service.listDocuments(companyId, tenderId, "ready", 20, null, null)

        then:
            1 * documentRepository.findByTenderId(tenderId, "ready", 20, null, null) >> [doc1]
            response.data.size() == 1
    }

    def "should throw NotFoundException when tender not found for list"() {
        given:
            tenderRepository.findById(tenderId) >> Optional.empty()

        when:
            service.listDocuments(companyId, tenderId, null, 20, null, null)

        then:
            thrown(NotFoundException)
    }

    def "should return empty list when no documents exist"() {
        given:
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findByTenderId(tenderId, null, 20, null, null) >> []

        when:
            def response = service.listDocuments(companyId, tenderId, null, 20, null, null)

        then:
            response.data.size() == 0
            response.pagination.cursor == null
    }

    // ==================== Delete Document ====================

    def "should soft-delete document and delete S3 object"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.of(mockDocumentRecord(documentId, tenderId, "ready", "s3/key"))

        when:
            service.deleteDocument(companyId, tenderId, documentId)

        then:
            1 * documentRepository.softDelete(documentId) >> 1
            1 * s3Client.deleteObject(_ as DeleteObjectRequest) >> DeleteObjectResponse.builder().build()
    }

    def "should throw NotFoundException when tender belongs to other company for delete"() {
        given:
            def documentId = "doc-1"
            def otherTender = mockTenderRecord(tenderId, "999")
            tenderRepository.findById(tenderId) >> Optional.of(otherTender)

        when:
            service.deleteDocument(companyId, tenderId, documentId)

        then:
            thrown(NotFoundException)
    }

    def "should throw NotFoundException when document not found for delete"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.empty()

        when:
            service.deleteDocument(companyId, tenderId, documentId)

        then:
            thrown(NotFoundException)
    }

    def "should throw NotFoundException when document belongs to other tender for delete"() {
        given:
            def documentId = "doc-1"
            def otherTenderId = UUID.randomUUID().toString()
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.of(mockDocumentRecord(documentId, otherTenderId, "ready", "s3/key"))

        when:
            service.deleteDocument(companyId, tenderId, documentId)

        then:
            thrown(NotFoundException)
    }

    // ==================== Download Presigned URL ====================

    def "should return presigned GET URL for ready document"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.of(mockDocumentRecord(documentId, tenderId, "ready", "s3/key"))

            def presignedGetObject = Mock(PresignedGetObjectRequest)
            presignedGetObject.url() >> new URL("https://s3.amazonaws.com/test-bucket/key")
            s3Presigner.presignGetObject(_ as GetObjectPresignRequest) >> presignedGetObject

        when:
            def response = service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then:
            response.url != null
            response.expiration != null
    }

    def "should throw NotFoundException when document not found for download"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.empty()

        when:
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then:
            thrown(NotFoundException)
    }

    def "should throw DocumentNotReadyException when status is uploading"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.of(mockDocumentRecord(documentId, tenderId, "uploading", "s3/key"))

        when:
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then:
            thrown(DocumentNotReadyException)
    }

    def "should throw DocumentNotReadyException when status is failed"() {
        given:
            def documentId = "doc-1"
            tenderRepository.findById(tenderId) >> Optional.of(mockTenderRecord(tenderId, companyIdStr))
            documentRepository.findById(documentId) >> Optional.of(mockDocumentRecord(documentId, tenderId, "failed", "s3/key"))

        when:
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then:
            thrown(DocumentNotReadyException)
    }
}
