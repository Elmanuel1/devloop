package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import software.amazon.awssdk.services.s3.S3Client
import spock.lang.Specification
import spock.lang.Subject

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Unit tests for {@link ExtractionWorker}.
 *
 * <p>AWS SDK's {@link software.amazon.awssdk.core.ResponseInputStream} is a final class
 * that Spock cannot mock. The fix is to spy on {@link ExtractionWorker} and stub the
 * package-private {@code openContentStream()} method, which isolates the S3 I/O layer
 * without requiring a mockable stream type.
 */
class ExtractionWorkerSpec extends Specification {

    DocumentClassifier documentClassifier = Mock()
    ReductoClient reductoClient = Mock()
    ExtractionFieldValidator fieldValidator = Mock()
    TenderDocumentRepository documentRepository = Mock()
    ReductoProperties reductoProperties = new ReductoProperties()
    S3Client s3Client = Mock()
    TenderFileProperties fileProperties = Mock()

    ObjectMapper mapper = new ObjectMapper()

    /** A minimal PDF-like stream — content doesn't matter because documentClassifier is mocked. */
    static final InputStream DUMMY_STREAM = new ByteArrayInputStream("dummy".bytes)

    static final String EXTRACTION_ID = "ext-001"
    static final String DOCUMENT_ID = "doc-001"
    static final String S3_KEY = "tenders/1/tender-1/doc-001/file.pdf"
    static final String BUCKET = "tosspaper-docs"
    static final String TASK_ID = "reducto-task-123"
    static final String WEBHOOK_URL = "https://my-service.example.com/internal/reducto/webhook"

    def setup() {
        reductoProperties.setBaseUrl("https://api.reducto.ai")
        reductoProperties.setApiKey("test-key")
        reductoProperties.setWebhookBaseUrl("https://my-service.example.com")
        reductoProperties.setWebhookPath("/internal/reducto/webhook")
        reductoProperties.setBatchSize(20)
        reductoProperties.setStaleMinutes(15)

        fileProperties.getUploadBucket() >> BUCKET
    }

    /**
     * Creates a worker Spy with {@code openContentStream} pre-stubbed to return a dummy stream.
     * Individual tests override this default stub when they need different behaviour.
     */
    private ExtractionWorker spyWorker(InputStream defaultStream = new ByteArrayInputStream("dummy".bytes)) {
        def spy = Spy(ExtractionWorker, constructorArgs: [
                documentClassifier, reductoClient, fieldValidator,
                documentRepository, reductoProperties, s3Client, fileProperties])
        spy.openContentStream(_, _, _) >> defaultStream
        return spy
    }

    // ── process: happy path ───────────────────────────────────────────────────

    def "TC-EW-01: process submits all documents and returns pipeline result"() {
        given: "extraction with two documents"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-A", "doc-B"])
            def worker = spyWorker()
            documentRepository.findById("doc-A") >> buildDocRecord("doc-A", "key/doc-A.pdf")
            documentRepository.findById("doc-B") >> buildDocRecord("doc-B", "key/doc-B.pdf")
            documentClassifier.isSupported(_, _) >> true
            reductoClient.submit(_ as ReductoSubmitRequest) >>
                    new ReductoSubmitResponse("task-A") >>
                    new ReductoSubmitResponse("task-B")

        when:
            def result = worker.process(extraction)

        then:
            result != null
            result.extractionId() == EXTRACTION_ID
    }

    def "TC-EW-02: process calls reductoClient.submit once per document"() {
        given: "extraction with three documents"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["d1", "d2", "d3"])
            def worker = spyWorker()
            documentRepository.findById(_) >> buildDocRecord("any", S3_KEY)
            documentClassifier.isSupported(_, _) >> true

        when:
            worker.process(extraction)

        then:
            3 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID)
    }

    // ── Hard cap ──────────────────────────────────────────────────────────────

    def "TC-EW-03: process caps at MAX_DOCUMENTS_PER_BATCH even if more are provided"() {
        given: "extraction with 25 documents — exceeds the 20-doc cap"
            def docIds = (1..25).collect { "doc-$it" as String }
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, docIds)
            def worker = spyWorker()
            documentRepository.findById(_) >> buildDocRecord("any", S3_KEY)
            documentClassifier.isSupported(_, _) >> true

        when:
            worker.process(extraction)

        then: "at most 20 documents submitted — no 21st call"
            20 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID)
    }

    def "TC-EW-04: MAX_DOCUMENTS_PER_BATCH constant is 20"() {
        expect:
            ExtractionWorker.MAX_DOCUMENTS_PER_BATCH == 20
    }

    // ── Unsupported document type ─────────────────────────────────────────────

    def "TC-EW-05: unsupported document type is skipped — Reducto is NOT called"() {
        given: "classifier rejects the document"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, [DOCUMENT_ID])
            def worker = spyWorker()
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.isSupported(DOCUMENT_ID, _) >> false

        when:
            def result = worker.process(extraction)

        then: "Reducto is never called for unsupported document"
            0 * reductoClient.submit(_)

        and: "process returns a result (skip is not a failure)"
            result != null
    }

    def "TC-EW-06: processDocument returns true for unsupported type (not a failure)"() {
        given:
            def worker = spyWorker()
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.isSupported(_, _) >> false

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            result  // skipped, not failed
            0 * reductoClient.submit(_)
    }

    // ── S3 failure ────────────────────────────────────────────────────────────

    def "TC-EW-07: S3 download failure causes processDocument to return false"() {
        given: "openContentStream returns null — simulates S3 failure"
            def worker = spyWorker(null) // null means download failed
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            !result
            0 * reductoClient.submit(_)
    }

    // ── Document lookup failure ───────────────────────────────────────────────

    def "TC-EW-08: document lookup failure causes processDocument to return false"() {
        given:
            def worker = spyWorker()
            documentRepository.findById(DOCUMENT_ID) >> {
                throw new NotFoundException("doc.notFound", "not found")
            }

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            !result
            0 * worker.openContentStream(_, _, _)
    }

    // ── Reducto submission failure ────────────────────────────────────────────

    def "TC-EW-09: Reducto client exception causes processDocument to return false"() {
        given:
            def worker = spyWorker()
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.isSupported(_, _) >> true
            reductoClient.submit(_ as ReductoSubmitRequest) >> {
                throw new ReductoClientException("HTTP 500")
            }

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            !result
    }

    // ── Reducto submit request fields ─────────────────────────────────────────

    def "TC-EW-10: submit request contains the correct extraction ID, document ID, S3 key, and webhook URL"() {
        given:
            def capturedRequests = []
            def worker = spyWorker()
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.isSupported(_, _) >> true
            reductoClient.submit(_ as ReductoSubmitRequest) >> { ReductoSubmitRequest req ->
                capturedRequests << req
                return new ReductoSubmitResponse(TASK_ID)
            }

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            capturedRequests.size() == 1
            with(capturedRequests[0]) {
                extractionId() == EXTRACTION_ID
                documentId() == DOCUMENT_ID
                s3Key() == S3_KEY
                webhookUrl() == WEBHOOK_URL
            }
    }

    // ── One failure does not abort other documents ────────────────────────────

    def "TC-EW-11: failure in one document does not prevent remaining documents from being processed"() {
        given: "three documents — first fails lookup, rest succeed"
            def docIds = ["fail-doc", "ok-doc-1", "ok-doc-2"]
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, docIds)
            def worker = spyWorker()
            documentRepository.findById("fail-doc") >> {
                throw new NotFoundException("doc.notFound", "not found")
            }
            documentRepository.findById("ok-doc-1") >> buildDocRecord("ok-doc-1", "key/ok1.pdf")
            documentRepository.findById("ok-doc-2") >> buildDocRecord("ok-doc-2", "key/ok2.pdf")
            documentClassifier.isSupported(_, _) >> true

        when:
            def result = worker.process(extraction)

        then: "the two successful documents were submitted"
            2 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID)

        and: "result is still returned — batch is not aborted"
            result != null
            result.extractionId() == EXTRACTION_ID
    }

    // ── validateAndWriteFields ────────────────────────────────────────────────

    def "TC-EW-12: validateAndWriteFields returns true when fieldValidator accepts payload"() {
        given:
            def payload = mapper.readTree('{"tender_title": "Bridge Contract"}')
            def worker = spyWorker()
            fieldValidator.isValid(DOCUMENT_ID, payload) >> true

        when:
            def result = worker.validateAndWriteFields(EXTRACTION_ID, DOCUMENT_ID, payload)

        then:
            result
    }

    def "TC-EW-13: validateAndWriteFields returns false when fieldValidator rejects payload"() {
        given:
            def worker = spyWorker()
            fieldValidator.isValid(DOCUMENT_ID, NullNode.instance) >> false

        when:
            def result = worker.validateAndWriteFields(EXTRACTION_ID, DOCUMENT_ID, NullNode.instance)

        then:
            !result
    }

    def "TC-EW-14: validateAndWriteFields delegates to fieldValidator — no direct validation logic in worker"() {
        given:
            def payload = mapper.readTree('{}')
            def worker = spyWorker()

        when:
            worker.validateAndWriteFields(EXTRACTION_ID, DOCUMENT_ID, payload)

        then: "fieldValidator is the single source of truth"
            1 * fieldValidator.isValid(DOCUMENT_ID, payload) >> false
    }

    // ── openContentStream is called with the correct S3 key ──────────────────

    def "TC-EW-15: processDocument calls openContentStream with the document s3Key from the DB record"() {
        given:
            def capturedKeys = []
            def worker = Spy(ExtractionWorker, constructorArgs: [
                    documentClassifier, reductoClient, fieldValidator,
                    documentRepository, reductoProperties, s3Client, fileProperties])
            worker.openContentStream(_, _, _) >> { String extId, String docId, String key ->
                capturedKeys << key
                return new ByteArrayInputStream("dummy".bytes)
            }
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.isSupported(_, _) >> true
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID)

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            capturedKeys == [S3_KEY]
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private static ExtractionWithDocs buildExtractionWithDocs(String id, List<String> docIds) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-1")
        record.setVersion(1)
        return new ExtractionWithDocs(record, docIds)
    }

    private static TenderDocumentsRecord buildDocRecord(String id, String s3Key) {
        def record = new TenderDocumentsRecord()
        record.setId(id)
        record.setTenderId("tender-1")
        record.setCompanyId("company-1")
        record.setFileName("file.pdf")
        record.setContentType("application/pdf")
        record.setFileSize(1024L)
        record.setS3Key(s3Key)
        record.setStatus("ready")
        return record
    }
}
