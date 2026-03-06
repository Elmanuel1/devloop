package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.models.exception.ReductoClientException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.precon.ConstructionDocumentType
import spock.lang.Specification

/**
 * Unit tests for {@link ExtractionWorker}.
 *
 * <p>{@link DocumentContentReader} is a plain mock — it isolates S3 I/O from
 * the worker's orchestration logic without any spy gymnastics.
 *
 * <p>Note: Spock evaluates stubs in the order they are registered (first match wins).
 * Tests that need to vary {@code contentReader} or {@code extractionRepository} behaviour
 * set stubs explicitly in their own {@code given:} block. No default stubs for those
 * collaborators are set in {@code setup()} to avoid hidden first-match conflicts.
 */
class ExtractionWorkerSpec extends Specification {

    DocumentClassifier documentClassifier = Mock()
    ReductoClient reductoClient = Mock()
    TenderDocumentRepository documentRepository = Mock()
    PreconExtractionRepository extractionRepository = Mock()
    DocumentContentReader contentReader = Mock()
    ReductoProperties reductoProperties = new ReductoProperties()
    ExtractionProcessingProperties processingProperties = new ExtractionProcessingProperties()

    static final byte[] DUMMY_BYTES = "dummy".bytes

    static final String EXTRACTION_ID = "ext-001"
    static final String DOCUMENT_ID = "doc-001"
    static final String S3_KEY = "tenders/1/tender-1/doc-001/file.pdf"
    static final String TASK_ID = "reducto-task-123"
    static final String FILE_ID = "reducto-file-456"
    static final String WEBHOOK_URL = "https://my-service.example.com/internal/reducto/webhook"
    static final ConstructionDocumentType BOQ = ConstructionDocumentType.BILL_OF_QUANTITIES

    def setup() {
        reductoProperties.setBaseUrl("https://api.reducto.ai")
        reductoProperties.setApiKey("test-key")
        reductoProperties.setWebhookBaseUrl("https://my-service.example.com")
        reductoProperties.setWebhookPath("/internal/reducto/webhook")
        reductoProperties.setDocumentCap(20)
        reductoProperties.setTaskTimeoutMinutes(15)
        reductoProperties.setTimeoutSeconds(30)

        processingProperties.setBatchSize(20)
        processingProperties.setStaleMinutes(15)
    }

    private ExtractionWorker buildWorker() {
        return new ExtractionWorker(
                documentClassifier, reductoClient,
                documentRepository, extractionRepository,
                contentReader, reductoProperties, processingProperties)
    }

    // ── process: happy path ───────────────────────────────────────────────────

    def "TC-EW-01: process submits all documents and returns pipeline result"() {
        given: "extraction with two documents"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-A", "doc-B"])
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById("doc-A") >> buildDocRecord("doc-A", "key/doc-A.pdf")
            documentRepository.findById("doc-B") >> buildDocRecord("doc-B", "key/doc-B.pdf")
            documentClassifier.classify(_, _) >> BOQ
            reductoClient.submit(_ as ReductoSubmitRequest) >>
                    new ReductoSubmitResponse("task-A", FILE_ID) >>
                    new ReductoSubmitResponse("task-B", FILE_ID)

        when:
            def result = worker.process(extraction)

        then:
            result != null
            result.extractionId() == EXTRACTION_ID
    }

    def "TC-EW-02: process calls reductoClient.submit once per document"() {
        given: "extraction with three documents"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["d1", "d2", "d3"])
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(_) >> buildDocRecord("any", S3_KEY)
            documentClassifier.classify(_, _) >> BOQ

        when:
            worker.process(extraction)

        then:
            3 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)
    }

    // ── Hard cap ──────────────────────────────────────────────────────────────

    def "TC-EW-03: process caps at processingProperties.batchSize even if more documents are provided"() {
        given: "extraction with 25 documents — exceeds the default 20-doc cap"
            def docIds = (1..25).collect { "doc-$it" as String }
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, docIds)
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(_) >> buildDocRecord("any", S3_KEY)
            documentClassifier.classify(_, _) >> BOQ

        when:
            worker.process(extraction)

        then: "at most 20 documents submitted — no 21st call"
            20 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)
    }

    def "TC-EW-04: process respects processingProperties.batchSize as the cap — not a hardcoded constant"() {
        given: "batch size configured to 5"
            processingProperties.setBatchSize(5)
            def docIds = (1..10).collect { "doc-$it" as String }
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, docIds)
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(_) >> buildDocRecord("any", S3_KEY)
            documentClassifier.classify(_, _) >> BOQ

        when:
            worker.process(extraction)

        then: "only 5 documents submitted"
            5 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)
    }

    // ── UNKNOWN document type → skip ─────────────────────────────────────────

    def "TC-EW-05: UNKNOWN document type is skipped — Reducto is NOT called"() {
        given: "classifier returns UNKNOWN"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, [DOCUMENT_ID])
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(DOCUMENT_ID, _) >> ConstructionDocumentType.UNKNOWN

        when:
            def result = worker.process(extraction)

        then: "Reducto is never called for UNKNOWN document"
            0 * reductoClient.submit(_)

        and: "process returns a result (skip is not a failure)"
            result != null
    }

    def "TC-EW-06: processDocument returns true for UNKNOWN type (not a failure)"() {
        given:
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> ConstructionDocumentType.UNKNOWN

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            result  // skipped, not failed
            0 * reductoClient.submit(_)
    }

    // ── S3 failure ────────────────────────────────────────────────────────────

    def "TC-EW-07: S3 download failure causes processDocument to return false"() {
        given: "contentReader returns null — simulates S3 failure"
            def worker = buildWorker()
            contentReader.read(_, _, _) >> null
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
            def worker = buildWorker()
            documentRepository.findById(DOCUMENT_ID) >> {
                throw new NotFoundException("doc.notFound", "not found")
            }

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            !result
            0 * contentReader.read(_, _, _)
    }

    // ── Reducto submission failure ────────────────────────────────────────────

    def "TC-EW-09: Reducto client exception causes processDocument to return false"() {
        given:
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            reductoClient.submit(_ as ReductoSubmitRequest) >> {
                throw new ReductoClientException("HTTP 500")
            }

        when:
            def result = worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            !result
    }

    // ── Reducto submit request fields ─────────────────────────────────────────

    def "TC-EW-10: submit request contains correct extraction ID, document ID, S3 key, webhook URL, and document type"() {
        given:
            def capturedRequests = []
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> ConstructionDocumentType.DRAWINGS
            reductoClient.submit(_ as ReductoSubmitRequest) >> { ReductoSubmitRequest req ->
                capturedRequests << req
                return new ReductoSubmitResponse(TASK_ID, FILE_ID)
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
                documentType() == ConstructionDocumentType.DRAWINGS
            }
    }

    def "TC-EW-10b: document type from classifier is forwarded verbatim to ReductoSubmitRequest"() {
        given: "classifier returns SPECIFICATIONS"
            def capturedTypes = []
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> ConstructionDocumentType.SPECIFICATIONS
            reductoClient.submit(_ as ReductoSubmitRequest) >> { ReductoSubmitRequest req ->
                capturedTypes << req.documentType()
                return new ReductoSubmitResponse(TASK_ID, FILE_ID)
            }

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then:
            capturedTypes == [ConstructionDocumentType.SPECIFICATIONS]
    }

    // ── One failure does not abort other documents ────────────────────────────

    def "TC-EW-11: failure in one document does not prevent remaining documents from being processed"() {
        given: "three documents — first fails lookup, rest succeed"
            def docIds = ["fail-doc", "ok-doc-1", "ok-doc-2"]
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, docIds)
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById("fail-doc") >> {
                throw new NotFoundException("doc.notFound", "not found")
            }
            documentRepository.findById("ok-doc-1") >> buildDocRecord("ok-doc-1", "key/ok1.pdf")
            documentRepository.findById("ok-doc-2") >> buildDocRecord("ok-doc-2", "key/ok2.pdf")
            documentClassifier.classify(_, _) >> BOQ

        when:
            def result = worker.process(extraction)

        then: "the two successful documents were submitted"
            2 * reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)

        and: "result is still returned — batch is not aborted"
            result != null
            result.extractionId() == EXTRACTION_ID
    }

    // ── External ID storage ───────────────────────────────────────────────────

    def "TC-EW-16: taskId from Reducto response is stored in extraction document_external_ids map"() {
        given:
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(EXTRACTION_ID) >> new HashMap<String, String>()
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, null)

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then: "the taskId is stored in the external IDs map"
            1 * extractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.get(DOCUMENT_ID) == TASK_ID
            }) >> 1
    }

    def "TC-EW-17: fileId from Reducto response is stored in tender_documents.external_file_id when non-blank"() {
        given:
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then: "external file ID is persisted on the document record"
            1 * documentRepository.updateExternalFileId(DOCUMENT_ID, FILE_ID) >> 1
    }

    def "TC-EW-18: null fileId from Reducto response does not call updateExternalFileId"() {
        given: "Reducto returns no file_id (null)"
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, null)

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then: "updateExternalFileId is never called when fileId is null"
            0 * documentRepository.updateExternalFileId(_, _)
    }

    // ── Defensive copy ────────────────────────────────────────────────────────

    def "TC-EW-19: submitToReducto does not mutate the map returned by getDocumentExternalIds"() {
        given: "existing external IDs with one entry"
            def original = new HashMap<String, String>([("other-doc"): "other-task"])
            def worker = buildWorker()
            contentReader.read(_, _, _) >> DUMMY_BYTES
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(EXTRACTION_ID) >> original
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, null)

        when:
            worker.processDocument(EXTRACTION_ID, DOCUMENT_ID)

        then: "the map passed to updateDocumentExternalIds contains both old and new entries"
            1 * extractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.get("other-doc") == "other-task" && m.get(DOCUMENT_ID) == TASK_ID
            }) >> 1

        and: "the original map returned by repository is not modified"
            !original.containsKey(DOCUMENT_ID)
    }

    // ── S3 key forwarded correctly ────────────────────────────────────────────

    def "TC-EW-15: processDocument passes the document s3Key from the DB record to contentReader"() {
        given:
            def capturedKeys = []
            def worker = buildWorker()
            contentReader.read(_, _, _) >> { String extId, String docId, String key ->
                capturedKeys << key
                return DUMMY_BYTES
            }
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentRepository.findById(DOCUMENT_ID) >> buildDocRecord(DOCUMENT_ID, S3_KEY)
            documentClassifier.classify(_, _) >> BOQ
            reductoClient.submit(_ as ReductoSubmitRequest) >> new ReductoSubmitResponse(TASK_ID, FILE_ID)

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
