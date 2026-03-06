package com.tosspaper.precon

import com.tosspaper.models.exception.ReductoClientException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import com.tosspaper.models.precon.ConstructionDocumentType
import spock.lang.Specification

/** Unit tests for {@link ExtractionWorker}. */
class ExtractionWorkerSpec extends Specification {

    DocumentClassifier documentClassifier = Mock()
    ExtractionClient extractionClient = Mock()
    TenderDocumentRepository documentRepository = Mock()
    PreconExtractionRepository extractionRepository = Mock()
    DocumentContentReader contentReader = Mock()
    ReductoProperties reductoProperties = new ReductoProperties()

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
        reductoProperties.setSvixChannel("precon-extraction")
        reductoProperties.setTaskTimeoutMinutes(15)
        reductoProperties.setTimeoutSeconds(30)
    }

    private ExtractionWorker buildWorker() {
        return new ExtractionWorker(
                documentClassifier, extractionClient,
                documentRepository, extractionRepository,
                contentReader, reductoProperties)
    }

    // ── process: happy path ───────────────────────────────────────────────────

    def "TC-EW-01: process submits a document and returns true"() {
        given: "a pre-loaded document record passed directly to the worker"
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentClassifier.classify(_, _) >> BOQ
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, FILE_ID)

        when:
            def result = worker.process(extraction, doc)

        then:
            result
    }

    def "TC-EW-02: process calls extractionClient.submit exactly once for one document"() {
        given:
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentClassifier.classify(_, _) >> BOQ

        when:
            worker.process(extraction, doc)

        then:
            1 * extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, FILE_ID)
    }

    // ── UNKNOWN document type → skip ─────────────────────────────────────────

    def "TC-EW-05: UNKNOWN document type is skipped — Reducto is NOT called"() {
        given: "classifier returns UNKNOWN"
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            documentClassifier.classify(DOCUMENT_ID, _) >> ConstructionDocumentType.UNKNOWN

        when:
            def result = worker.process(extraction, doc)

        then: "Reducto is never called for UNKNOWN document"
            0 * extractionClient.submit(_)

        and: "skip is not a failure — returns true"
            result
    }

    // ── S3 failure ────────────────────────────────────────────────────────────

    def "TC-EW-07: S3 download failure propagates — no Reducto call"() {
        given: "contentReader throws — simulates S3 failure"
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> { throw new RuntimeException("S3 unavailable") }

        when:
            worker.process(extraction, doc)

        then:
            thrown(RuntimeException)
            0 * extractionClient.submit(_)
    }

    // ── Reducto submission failure ────────────────────────────────────────────

    def "TC-EW-09: Reducto client exception causes process to return false"() {
        given:
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            documentClassifier.classify(_, _) >> BOQ
            extractionClient.submit(_ as ExtractionSubmitRequest) >> {
                throw new ReductoClientException("HTTP 500")
            }

        when:
            def result = worker.process(extraction, doc)

        then:
            !result
    }

    // ── Reducto submit request fields ─────────────────────────────────────────

    def "TC-EW-10: submit request contains correct extraction ID, document ID, S3 key, webhook URL, and document type"() {
        given:
            def capturedRequests = []
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentClassifier.classify(_, _) >> ConstructionDocumentType.DRAWINGS
            extractionClient.submit(_ as ExtractionSubmitRequest) >> { ExtractionSubmitRequest req ->
                capturedRequests << req
                return new ExtractionSubmitResponse(TASK_ID, FILE_ID)
            }

        when:
            worker.process(extraction, doc)

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

    def "TC-EW-10b: document type from classifier is forwarded verbatim to ExtractionSubmitRequest"() {
        given: "classifier returns SPECIFICATIONS"
            def capturedTypes = []
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentClassifier.classify(_, _) >> ConstructionDocumentType.SPECIFICATIONS
            extractionClient.submit(_ as ExtractionSubmitRequest) >> { ExtractionSubmitRequest req ->
                capturedTypes << req.documentType()
                return new ExtractionSubmitResponse(TASK_ID, FILE_ID)
            }

        when:
            worker.process(extraction, doc)

        then:
            capturedTypes == [ConstructionDocumentType.SPECIFICATIONS]
    }

    // ── External ID storage ───────────────────────────────────────────────────

    def "TC-EW-16: taskId from Reducto response is stored in extraction document_external_ids map"() {
        given:
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(EXTRACTION_ID) >> new HashMap<String, String>()
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, null)

        when:
            worker.process(extraction, doc)

        then: "the taskId is stored in the external IDs map"
            1 * extractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.get(DOCUMENT_ID) == TASK_ID
            }) >> 1
    }

    def "TC-EW-17: fileId from Reducto response is stored in tender_documents.external_file_id when non-blank"() {
        given:
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, FILE_ID)

        when:
            worker.process(extraction, doc)

        then: "external file ID is persisted on the document record"
            1 * documentRepository.updateExternalFileId(DOCUMENT_ID, FILE_ID) >> 1
    }

    def "TC-EW-18: null fileId from Reducto response does not call updateExternalFileId"() {
        given: "Reducto returns no file_id (null)"
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, null)

        when:
            worker.process(extraction, doc)

        then: "updateExternalFileId is never called when fileId is null"
            0 * documentRepository.updateExternalFileId(_, _)
    }

    // ── Defensive copy ────────────────────────────────────────────────────────

    def "TC-EW-19: submitToReducto does not mutate the map returned by getDocumentExternalIds"() {
        given: "existing external IDs with one entry"
            def original = new HashMap<String, String>([("other-doc"): "other-task"])
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> DUMMY_BYTES
            documentClassifier.classify(_, _) >> BOQ
            extractionRepository.getDocumentExternalIds(EXTRACTION_ID) >> original
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, null)

        when:
            worker.process(extraction, doc)

        then: "the map passed to updateDocumentExternalIds contains both old and new entries"
            1 * extractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.get("other-doc") == "other-task" && m.get(DOCUMENT_ID) == TASK_ID
            }) >> 1

        and: "the original map returned by repository is not modified"
            !original.containsKey(DOCUMENT_ID)
    }

    // ── S3 key forwarded correctly ────────────────────────────────────────────

    def "TC-EW-15: process passes the document s3Key from the record to contentReader"() {
        given:
            def capturedKeys = []
            def doc = buildDocRecord(DOCUMENT_ID, S3_KEY)
            def extraction = buildExtractionDocument(EXTRACTION_ID, [doc])
            def worker = buildWorker()
            contentReader.read(_) >> { String key ->
                capturedKeys << key
                return DUMMY_BYTES
            }
            extractionRepository.getDocumentExternalIds(_) >> new HashMap<String, String>()
            extractionRepository.updateDocumentExternalIds(_, _) >> 1
            documentClassifier.classify(_, _) >> BOQ
            extractionClient.submit(_ as ExtractionSubmitRequest) >> new ExtractionSubmitResponse(TASK_ID, FILE_ID)

        when:
            worker.process(extraction, doc)

        then:
            capturedKeys == [S3_KEY]
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private static ExtractionDocument buildExtractionDocument(String id, List<TenderDocumentsRecord> docs) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-1")
        record.setVersion(1)
        return new ExtractionDocument(record, docs)
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
