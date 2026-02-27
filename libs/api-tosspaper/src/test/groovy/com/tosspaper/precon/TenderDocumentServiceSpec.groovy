package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.models.exception.CannotDeleteException
import com.tosspaper.models.exception.DocumentNotReadyException
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.jooq.tables.records.TendersRecord
import com.tosspaper.precon.generated.model.ContentType
import com.tosspaper.precon.generated.model.DownloadUrlResponse
import com.tosspaper.precon.generated.model.Pagination
import com.tosspaper.precon.generated.model.PresignedUrlRequest
import com.tosspaper.precon.generated.model.PresignedUrlResponse
import com.tosspaper.precon.generated.model.TenderDocument
import com.tosspaper.precon.generated.model.TenderDocumentListResponse
import com.tosspaper.precon.generated.model.TenderDocumentStatus
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import spock.lang.Specification

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class TenderDocumentServiceSpec extends Specification {

    TenderRepository tenderRepository
    TenderDocumentRepository tenderDocumentRepository
    TenderDocumentMapper tenderDocumentMapper
    TenderFileProperties fileProperties
    S3Presigner s3Presigner
    S3Client s3Client
    TenderDocumentServiceImpl service

    def setup() {
        tenderRepository = Mock()
        tenderDocumentRepository = Mock()
        tenderDocumentMapper = Mock()
        fileProperties = new TenderFileProperties()
        fileProperties.setUploadBucket("test-bucket")
        s3Presigner = Mock()
        s3Client = Mock()
        service = new TenderDocumentServiceImpl(
            tenderRepository, tenderDocumentRepository, tenderDocumentMapper,
            fileProperties, s3Presigner, s3Client)
    }

    // ==================== getUploadPresignedUrl ====================

    def "should create document record and return presigned URL with documentId and expiration"() {
        given: "a valid upload request for a tender belonging to the company"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "42")

            def request = new PresignedUrlRequest()
            request.setFileName("proposal.pdf")
            request.setContentType(ContentType.APPLICATION_PDF)
            request.setFileSize(2048)

            def presignedPut = Mock(PresignedPutObjectRequest)
            presignedPut.url() >> new URL("https://s3.amazonaws.com/test-bucket/tenders/42/${tenderId}/doc-id/proposal.pdf")
            presignedPut.expiration() >> Instant.now().plusSeconds(600)

        when: "requesting an upload presigned URL"
            def result = service.getUploadPresignedUrl(companyId, tenderId, request)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "presigned URL is generated before insert"
            1 * s3Presigner.presignPutObject(_ as PutObjectPresignRequest) >> presignedPut

        and: "mapper builds the record and it is inserted"
            1 * tenderDocumentMapper.toRecord(request, _, tenderId, "42", _) >> { args ->
                createDocumentRecord(args[1], tenderId, "42")
            }
            1 * tenderDocumentRepository.insert(_) >> { TenderDocumentsRecord r -> r }

        and: "response has presignedUrl, documentId and expiration"
            with(result) {
                presignedUrl != null
                presignedUrl.toString().startsWith("https://s3.amazonaws.com/test-bucket/")
                documentId != null
                expiration != null
            }
    }

    def "should throw NotFoundException when tender belongs to different company"() {
        given: "a tender owned by a different company"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "999")

            def request = new PresignedUrlRequest()
            request.setFileName("proposal.pdf")
            request.setContentType(ContentType.APPLICATION_PDF)
            request.setFileSize(2048)

        when: "requesting an upload presigned URL"
            service.getUploadPresignedUrl(companyId, tenderId, request)

        then: "tender is fetched"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "no document is created and no presigned URL is generated"
            0 * s3Presigner.presignPutObject(_)
            0 * tenderDocumentMapper.toRecord(_, _, _, _, _)
            0 * tenderDocumentRepository.insert(_)
    }

    // ==================== listDocuments ====================

    def "should return list of documents with pagination"() {
        given: "a tender with two documents"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "42")
            def doc1 = createDocumentRecord("doc-1", tenderId, "42")
            def doc2 = createDocumentRecord("doc-2", tenderId, "42")
            def records = [doc1, doc2]
            def dto1 = new TenderDocument()
            def dto2 = new TenderDocument()
            def dtos = [dto1, dto2]

        when: "listing documents"
            def result = service.listDocuments(companyId, tenderId, 20, null, null)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "repository is queried with correct parameters"
            1 * tenderDocumentRepository.findByTenderId(tenderId, null, 20, null, null) >> records

        and: "mapper converts records to DTOs"
            1 * tenderDocumentMapper.toDtoList(records) >> dtos

        and: "response has data and null cursor (no more results)"
            with(result) {
                data.size() == 2
                pagination.cursor == null
            }
    }

    def "should set cursor when repo returns limit+1 records indicating more results exist"() {
        given: "a tender with more results than requested limit"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "42")
            // repo returns limit+1 = 3 records when limit=2
            def doc1 = createDocumentRecord("doc-1", tenderId, "42")
            def doc2 = createDocumentRecord("doc-2", tenderId, "42")
            def doc3 = createDocumentRecord("doc-3", tenderId, "42")
            def records = [doc1, doc2, doc3]
            def dto1 = new TenderDocument()
            def dto2 = new TenderDocument()
            def dtos = [dto1, dto2]

        when: "listing documents with limit 2"
            def result = service.listDocuments(companyId, tenderId, 2, null, null)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "repository is queried with limit 2"
            1 * tenderDocumentRepository.findByTenderId(tenderId, null, 2, null, null) >> records

        and: "mapper receives only the first 2 records (trimmed list)"
            1 * tenderDocumentMapper.toDtoList([doc1, doc2]) >> dtos

        and: "response has cursor set (more results exist)"
            with(result) {
                data.size() == 2
                pagination.cursor != null
            }
    }

    def "should set null cursor when repo returns exactly limit records indicating no more results"() {
        given: "a tender with exactly the requested limit of results"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "42")
            def doc1 = createDocumentRecord("doc-1", tenderId, "42")
            def doc2 = createDocumentRecord("doc-2", tenderId, "42")
            def records = [doc1, doc2]
            def dtos = [new TenderDocument(), new TenderDocument()]

        when: "listing documents with limit 2"
            def result = service.listDocuments(companyId, tenderId, 2, null, null)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "repository returns exactly limit records"
            1 * tenderDocumentRepository.findByTenderId(tenderId, null, 2, null, null) >> records

        and: "mapper receives all records"
            1 * tenderDocumentMapper.toDtoList(records) >> dtos

        and: "pagination cursor is null"
            with(result) {
                data.size() == 2
                pagination.cursor == null
            }
    }

    def "should throw NotFoundException when listing documents for tender belonging to different company"() {
        given: "a tender owned by a different company"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def tender = createTenderRecord(tenderId, "999")

        when: "listing documents"
            service.listDocuments(companyId, tenderId, 20, null, null)

        then: "tender is fetched"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "repository is never queried for documents"
            0 * tenderDocumentRepository.findByTenderId(_, _, _, _, _)
    }

    // ==================== deleteDocument ====================

    def "should soft-delete document record and delete S3 object"() {
        given: "an existing document belonging to the tender"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, tenderId, "42")

        when: "deleting the document"
            service.deleteDocument(companyId, tenderId, documentId)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found by ID"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "document is soft-deleted first"
            1 * tenderDocumentRepository.softDelete(documentId) >> 1

        and: "S3 object is deleted with the correct key and bucket"
            1 * s3Client.deleteObject({ DeleteObjectRequest r ->
                r.bucket() == "test-bucket" &&
                r.key() == document.s3Key
            })
    }

    def "should throw NotFoundException when document not found for deletion"() {
        given: "a document that does not exist"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "nonexistent-doc"
            def tender = createTenderRecord(tenderId, "42")

        when: "deleting the document"
            service.deleteDocument(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document lookup throws NotFoundException"
            1 * tenderDocumentRepository.findById(documentId) >> { throw new NotFoundException("api.document.notFound", "Document not found") }

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "soft delete and S3 delete are never called"
            0 * tenderDocumentRepository.softDelete(_)
            0 * s3Client.deleteObject(_)
    }

    def "should throw NotFoundException when document belongs to different tender"() {
        given: "a document that belongs to a different tender"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, "different-tender-id", "42")

        when: "deleting the document"
            service.deleteDocument(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found but belongs to a different tender"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "soft delete and S3 delete are never called"
            0 * tenderDocumentRepository.softDelete(_)
            0 * s3Client.deleteObject(_)
    }

    def "should complete soft-delete even if S3 delete fails"() {
        given: "an existing document where S3 delete will fail"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, tenderId, "42")

        when: "deleting the document"
            service.deleteDocument(companyId, tenderId, documentId)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "soft delete executes successfully"
            1 * tenderDocumentRepository.softDelete(documentId) >> 1

        and: "S3 delete is attempted but throws an exception (which is swallowed)"
            1 * s3Client.deleteObject(_ as DeleteObjectRequest) >> { throw new RuntimeException("S3 connection error") }

        and: "no exception propagates to the caller"
            noExceptionThrown()
    }

    def "should throw CannotDeleteException when tender is in final status"() {
        given: "a tender in 'won' status"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            tender.setStatus("won")

        when: "deleting the document"
            service.deleteDocument(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "CannotDeleteException is thrown"
            thrown(CannotDeleteException)

        and: "document is never looked up or deleted"
            0 * tenderDocumentRepository.findById(_)
            0 * tenderDocumentRepository.softDelete(_)
            0 * s3Client.deleteObject(_)
    }

    // ==================== getDownloadPresignedUrl ====================

    def "should return presigned download URL when document status is ready"() {
        given: "a ready document belonging to the tender"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, tenderId, "42")
            document.setStatus("ready")

            def presignedGet = Mock(PresignedGetObjectRequest)
            presignedGet.url() >> new URL("https://s3.amazonaws.com/test-bucket/${document.s3Key}?X-Amz-Signature=abc")
            presignedGet.expiration() >> Instant.now().plusSeconds(300)

        when: "requesting a download presigned URL"
            def result = service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender ownership is verified"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "presigned GET URL is generated with the correct S3 key and bucket"
            1 * s3Presigner.presignGetObject(_ as GetObjectPresignRequest) >> presignedGet

        and: "response has url and expiration"
            with(result) {
                url != null
                url.toString().startsWith("https://s3.amazonaws.com/test-bucket/")
                expiration != null
            }
    }

    def "should throw DocumentNotReadyException when document status is uploading"() {
        given: "a document with uploading status"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, tenderId, "42")
            document.setStatus("uploading")

        when: "requesting a download presigned URL"
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found with non-ready status"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "DocumentNotReadyException is thrown"
            thrown(DocumentNotReadyException)

        and: "no presigned URL is generated"
            0 * s3Presigner.presignGetObject(_)
    }

    def "should throw DocumentNotReadyException when document status is processing"() {
        given: "a document with processing status"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, tenderId, "42")
            document.setStatus("processing")

        when: "requesting a download presigned URL"
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found with processing status"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "DocumentNotReadyException is thrown"
            thrown(DocumentNotReadyException)

        and: "no presigned URL is generated"
            0 * s3Presigner.presignGetObject(_)
    }

    def "should throw NotFoundException when document not found for download"() {
        given: "a document that does not exist"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "nonexistent-doc"
            def tender = createTenderRecord(tenderId, "42")

        when: "requesting a download presigned URL"
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document lookup throws NotFoundException"
            1 * tenderDocumentRepository.findById(documentId) >> { throw new NotFoundException("api.document.notFound", "Document not found") }

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "no presigned URL is generated"
            0 * s3Presigner.presignGetObject(_)
    }

    def "should throw NotFoundException when download requested for document belonging to different tender"() {
        given: "a document that belongs to a different tender"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "42")
            def document = createDocumentRecord(documentId, "other-tender-id", "42")
            document.setStatus("ready")

        when: "requesting a download presigned URL"
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender is found"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "document is found but belongs to a different tender"
            1 * tenderDocumentRepository.findById(documentId) >> document

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "no presigned URL is generated"
            0 * s3Presigner.presignGetObject(_)
    }

    def "should throw NotFoundException when download tender belongs to different company"() {
        given: "a tender owned by a different company"
            def companyId = 42L
            def tenderId = "tender-abc-123"
            def documentId = "doc-111"
            def tender = createTenderRecord(tenderId, "999")

        when: "requesting a download presigned URL"
            service.getDownloadPresignedUrl(companyId, tenderId, documentId)

        then: "tender is found but belongs to a different company"
            1 * tenderRepository.findById(tenderId) >> tender

        and: "NotFoundException is thrown"
            thrown(NotFoundException)

        and: "document is never looked up"
            0 * tenderDocumentRepository.findById(_)
    }

    // ==================== Helper Methods ====================

    private static TendersRecord createTenderRecord(String id, String companyId) {
        def record = new TendersRecord()
        record.setId(id)
        record.setCompanyId(companyId)
        record.setName("Test Tender")
        record.setStatus("pending")
        record.setVersion(0)
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static TenderDocumentsRecord createDocumentRecord(String id, String tenderId, String companyId) {
        def record = new TenderDocumentsRecord()
        record.setId(id)
        record.setTenderId(tenderId)
        record.setCompanyId(companyId)
        record.setFileName("test.pdf")
        record.setContentType("application/pdf")
        record.setFileSize(1024L)
        record.setS3Key("tenders/${companyId}/${tenderId}/${id}/test.pdf")
        record.setStatus("ready")
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }
}
