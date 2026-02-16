package com.tosspaper.aiengine.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult
import com.tosspaper.aiengine.client.common.dto.PreparationResult
import com.tosspaper.aiengine.client.common.dto.StartTaskResult
import com.tosspaper.aiengine.client.common.exception.TaskNotFoundException
import com.tosspaper.aiengine.dto.StartTaskRequest
import com.tosspaper.aiengine.extractors.DocumentExtractor
import com.tosspaper.aiengine.loaders.JsonSchemaLoader
import com.tosspaper.aiengine.loaders.PromptLoader
import com.tosspaper.aiengine.repository.DocumentApprovalRepository
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.models.domain.*
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.Extraction
import com.tosspaper.models.messaging.MessagePublisher
import com.tosspaper.models.service.DocumentPartComparisonService
import com.tosspaper.models.service.EmailMetadataService
import com.tosspaper.models.service.PurchaseOrderLookupService
import com.tosspaper.models.service.StorageService
import com.tosspaper.models.storage.DownloadResult
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.jooq.DSLContext
import org.jooq.impl.DSL
import spock.lang.Specification
import spock.lang.Subject

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit tests for ExtractionService.
 * Tests the complete lifecycle of extraction task orchestration.
 */
class ExtractionServiceSpec extends Specification {

    ExtractionTaskRepository extractionTaskRepository = Mock()
    ProcessingService processingService = Mock()
    JsonSchemaLoader jsonSchemaLoader = Mock()
    PromptLoader promptLoader = Mock()
    StorageService s3StorageService = Mock()
    MessagePublisher publisher = Mock()
    EmailMetadataService emailMetadataService = Mock()
    DocumentExtractor documentExtractor = Mock()
    DocumentApprovalRepository documentApprovalRepository = Mock()
    ObjectMapper objectMapper = new ObjectMapper()
    DSLContext dslContext = Mock()
    PurchaseOrderLookupService poService = Mock()
    DocumentPartComparisonService comparisonService = Mock()
    DocumentPartComparisonRepository comparisonRepository = Mock()
    ObservationRegistry observationRegistry = Mock()

    @Subject
    ExtractionService service

    def setup() {
        service = new ExtractionService(
            extractionTaskRepository,
            processingService,
            jsonSchemaLoader,
            promptLoader,
            s3StorageService,
            publisher,
            emailMetadataService,
            documentExtractor,
            documentApprovalRepository,
            objectMapper,
            dslContext,
            poService,
            comparisonService,
            comparisonRepository,
            observationRegistry
        )
        // Default stub for observation registry
        observationRegistry.getCurrentObservation() >> null
    }

    // ==================== EXTRACT ENTRY POINT TESTS ====================

    def "extract should throw exception when email metadata not found"() {
        given: "assigned ID and storage key"
            def assignedId = "attach-123"
            def storageKey = "s3://bucket/file.pdf"

        and: "email metadata service returns empty"
            emailMetadataService.getEmailMetadataByAttachmentId(assignedId) >> Optional.empty()

        when: "extracting"
            service.extract(assignedId, storageKey)

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Email message not found")
    }

    def "extract should handle COMPLETED status"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has COMPLETED status"
            def completedTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.COMPLETED)
                .build()
            extractionTaskRepository.save(_) >> completedTask

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "no further processing"
            0 * processingService._
            0 * s3StorageService._
    }

    def "extract should handle CANCELLED status"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has CANCELLED status"
            def cancelledTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.CANCELLED)
                .build()
            extractionTaskRepository.save(_) >> cancelledTask

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "no further processing"
            0 * processingService._
    }

    def "extract should handle MANUAL_INTERVENTION status"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task requires manual intervention"
            def manualTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.MANUAL_INTERVENTION)
                .build()
            extractionTaskRepository.save(_) >> manualTask

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "no further processing"
            0 * processingService._
    }

    def "extract should handle PENDING status by downloading and starting"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PENDING status"
            def pendingTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.PENDING)
                .build()
            extractionTaskRepository.save(_) >> pendingTask

        and: "download succeeds"
            def fileObject = FileObject.builder()
                .assignedId("attach-123")
                .contentType("application/pdf")
                .content(new byte[100])
                .build()
            s3StorageService.download(_) >>
                DownloadResult.success("s3://bucket/file.pdf", fileObject)

        and: "preparation succeeds"
            processingService.prepareTask(_) >> PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .preparationId("prep-123")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build()

        and: "task update succeeds"
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> pendingTask

        and: "start task succeeds"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"
            processingService.startTask(_) >> StartTaskResult.success("task-123")

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "download and start execution is called"
            noExceptionThrown()
    }

    def "extract should handle PREPARE_SUCCEEDED status by starting extraction"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PREPARE_SUCCEEDED status"
            def preparedTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .preparationId("prep-123")
                .build()
            extractionTaskRepository.save(_) >> preparedTask

        and: "start task succeeds"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"
            processingService.startTask(_) >> StartTaskResult.success("task-123")
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> preparedTask

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "no exception"
            noExceptionThrown()
    }

    def "extract should handle STARTED status by checking completion"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has STARTED status"
            def startedTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.STARTED)
                .taskId("task-123")
                .preparationId("prep-123")
                .build()
            extractionTaskRepository.save(_) >> startedTask

        and: "completion check returns completed task"
            def completedResult = ExtractTaskResult.builder()
                .taskId("task-123")
                .status(ExtractionStatus.COMPLETED)
                .found(true)
                .type("INVOICE")
                .rawResponse('{"documentNumber": "INV-001"}')
                .build()
            processingService.getExtractTask("task-123") >> completedResult

        and: "extraction parsing succeeds"
            documentExtractor.extract(_) >> '{"documentNumber": "INV-001"}'

        and: "no PO found"
            poService.getPoWithItemsByPoNumber(_, _) >> Optional.empty()

        and: "transaction succeeds"
            def txContext = Mock(org.jooq.Configuration)
            txContext.dsl() >> dslContext
            dslContext.transactionResult(_) >> { args ->
                def lambda = args[0] as org.jooq.TransactionalCallable
                lambda.run(txContext)
            }

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "completion status is checked"
            noExceptionThrown()
    }

    def "extract should handle PREPARE_UNKNOWN by checking file existence"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PREPARE_UNKNOWN status"
            def unknownTask = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.PREPARE_UNKNOWN)
                .build()
            extractionTaskRepository.save(_) >> unknownTask

        and: "file search finds the file"
            processingService.searchExecutionFile(_) >> ExtractTaskResult.builder()
                .found(true)
                .fileId("file-123")
                .build()

        and: "start task succeeds"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"
            processingService.startTask(_) >> StartTaskResult.success("task-123")
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> unknownTask

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "file search is performed and extraction started"
            noExceptionThrown()
    }

    // ==================== DOWNLOAD AND START EXECUTION TESTS ====================

    def "downloadAndStartExecution should handle S3 download failure"() {
        given: "email metadata exists and task is pending"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PENDING status"
            def task = createTask("attach-123")
            extractionTaskRepository.save(_) >> task

        and: "download fails"
            def storageKey = "s3://bucket/file.pdf"
            s3StorageService.download(_) >> DownloadResult.failure(storageKey, "S3 error")

        when: "extracting"
            service.extract("attach-123", storageKey)

        then: "task is marked as PREPARE_FAILED"
            1 * extractionTaskRepository.update({ it.status == ExtractionStatus.PREPARE_FAILED }, _)
    }

    def "downloadAndStartExecution should handle preparation failure"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PENDING status"
            def task = createTask("attach-123")
            extractionTaskRepository.save(_) >> task

        and: "download succeeds but preparation fails"
            def storageKey = "s3://bucket/file.pdf"
            def fileObject = FileObject.builder()
                .contentType("application/pdf")
                .content(new byte[100])
                .build()
            s3StorageService.download(_) >> DownloadResult.success(storageKey, fileObject)
            processingService.prepareTask(_) >> PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_FAILED)
                .error("Preparation error")
                .build()

        when: "extracting"
            service.extract("attach-123", storageKey)

        then: "task is marked as PREPARE_FAILED"
            1 * extractionTaskRepository.update({ it.status == ExtractionStatus.PREPARE_FAILED }, _)
    }

    def "downloadAndStartExecution should complete successfully"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PENDING status"
            def task = createTask("attach-123")
            extractionTaskRepository.save(_) >> task

        and: "download succeeds"
            def storageKey = "s3://bucket/file.pdf"
            def fileObject = FileObject.builder()
                .contentType("application/pdf")
                .content(new byte[100])
                .build()
            s3StorageService.download(_) >> DownloadResult.success(storageKey, fileObject)

        and: "preparation succeeds"
            processingService.prepareTask(_) >> PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .preparationId("prep-123")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build()

        and: "start task succeeds"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"
            processingService.startTask(_) >> StartTaskResult.success("task-123")
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> task

        when: "extracting"
            service.extract("attach-123", storageKey)

        then: "all steps complete successfully"
            noExceptionThrown()
    }

    // ==================== START EXTRACTION TESTS ====================

    def "startExtraction should handle start task failure"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PREPARE_SUCCEEDED status"
            def task = createTask("attach-123").toBuilder()
                .preparationId("prep-123")
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .build()
            extractionTaskRepository.save(_) >> task

        and: "schema and prompt load successfully"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"

        and: "start task fails"
            processingService.startTask(_) >> StartTaskResult.failure("Start error", new RuntimeException("Start error"))

        when: "starting extraction"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "task is marked as START_TASK_FAILED"
            1 * extractionTaskRepository.update({ it.status == ExtractionStatus.START_TASK_FAILED }, _)
    }

    def "startExtraction should succeed with valid task"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "saved task has PREPARE_SUCCEEDED status"
            def task = createTask("attach-123").toBuilder()
                .preparationId("prep-123")
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .build()
            extractionTaskRepository.save(_) >> task

        and: "schema and prompt load successfully"
            jsonSchemaLoader.loadSchema() >> '{"type": "object"}'
            promptLoader.loadPrompt() >> "Extract all fields"

        and: "start task succeeds"
            processingService.startTask(_) >> StartTaskResult.success("task-123")
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> task

        when: "starting extraction"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "task is started successfully"
            noExceptionThrown()
    }

    // ==================== CHECK COMPLETION STATUS TESTS ====================

    def "checkCompletionStatus should use task ID when available"() {
        given: "task with task ID"
            def task = createTask("attach-123").toBuilder()
                .taskId("task-123")
                .status(ExtractionStatus.STARTED)
                .preparationId("prep-123")
                .build()
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)
            extractionTaskRepository.save(_) >> task

        and: "direct lookup succeeds"
            def result = ExtractTaskResult.builder()
                .taskId("task-123")
                .status(ExtractionStatus.COMPLETED)
                .found(true)
                .type("INVOICE")
                .rawResponse('{"documentNumber": "INV-001"}')
                .build()
            processingService.getExtractTask("task-123") >> result

        and: "extraction parsing succeeds"
            documentExtractor.extract(_) >> '{"documentNumber": "INV-001"}'

        and: "no PO found"
            poService.getPoWithItemsByPoNumber(_, _) >> Optional.empty()

        and: "transaction succeeds"
            def txContext = Mock(org.jooq.Configuration)
            txContext.dsl() >> dslContext
            dslContext.transactionResult(_) >> { args ->
                def lambda = args[0] as org.jooq.TransactionalCallable
                lambda.run(txContext)
            }

        when: "checking completion"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "direct lookup is used, search is not called"
            0 * processingService.searchExecutionTask(_)
    }

    def "checkCompletionStatus should search when task ID is null"() {
        given: "task without task ID"
            def task = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.START_TASK_UNKNOWN)
                .preparationId("prep-123")
                .build()
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)
            extractionTaskRepository.save(_) >> task

        and: "extraction parsing succeeds"
            documentExtractor.extract(_) >> '{"documentNumber": "INV-001"}'

        and: "no PO found"
            poService.getPoWithItemsByPoNumber(_, _) >> Optional.empty()

        and: "transaction succeeds"
            def txContext = Mock(org.jooq.Configuration)
            txContext.dsl() >> dslContext
            dslContext.transactionResult(_) >> { args ->
                def lambda = args[0] as org.jooq.TransactionalCallable
                lambda.run(txContext)
            }

        when: "checking completion"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "search is used - combine verification with stub return"
            1 * processingService.searchExecutionTask(_) >> ExtractTaskResult.builder()
                .taskId("task-123")
                .status(ExtractionStatus.COMPLETED)
                .found(true)
                .type("INVOICE")
                .rawResponse('{"documentNumber": "INV-001"}')
                .build()
    }

    // ==================== PROCESS TASK RESULT TESTS ====================

    def "processTaskResult should handle completed task with PO match"() {
        given: "completed task result with PO"
            def task = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.STARTED)
                .taskId("task-123")
                .preparationId("prep-123")
                .companyId(1L)
                .build()
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)
            extractionTaskRepository.save(_) >> task

        and: "task result is completed"
            def result = ExtractTaskResult.builder()
                .taskId("task-123")
                .status(ExtractionStatus.COMPLETED)
                .found(true)
                .type("INVOICE")
                .rawResponse('{"customerPONumber": "PO-123", "documentNumber": "INV-001"}')
                .build()
            processingService.getExtractTask("task-123") >> result

        and: "extraction parsing succeeds"
            def extractionJson = '{"customerPONumber": "PO-123", "documentNumber": "INV-001"}'
            documentExtractor.extract(_) >> extractionJson

        and: "PO is found"
            def po = PurchaseOrder.builder()
                .id("po-id-123")
                .displayId("PO-123")
                .projectId("proj-456")
                .build()
            poService.getPoWithItemsByPoNumber(1L, "PO-123") >> Optional.of(po)

        and: "comparison succeeds"
            def comparison = new Comparison()
            comparison.setDocumentId("attach-123")
            comparisonService.compareDocumentParts(_) >> comparison

        and: "transaction succeeds"
            def txContext = Mock(org.jooq.Configuration)
            txContext.dsl() >> dslContext
            dslContext.transactionResult(_) >> { args ->
                def lambda = args[0] as org.jooq.TransactionalCallable
                lambda.run(txContext)
            }

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "task is updated with PO match"
            1 * extractionTaskRepository.update(dslContext, { ExtractionTask t ->
                t.status == ExtractionStatus.COMPLETED &&
                t.purchaseOrderId == "po-id-123" &&
                t.poNumber == "PO-123" &&
                t.matchType == MatchType.DIRECT &&
                t.projectId == "proj-456"
            }, _)
            1 * comparisonRepository.upsert(dslContext, "attach-123", comparison)
            1 * documentApprovalRepository.createInitialApproval(dslContext, "PO-123", "INV-001", "attach-123", _, _, _)
    }

    def "processTaskResult should handle completed task without PO match"() {
        given: "completed task result without PO"
            def task = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.STARTED)
                .taskId("task-123")
                .preparationId("prep-123")
                .companyId(1L)
                .build()
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)
            extractionTaskRepository.save(_) >> task

        and: "task result is completed"
            def result = ExtractTaskResult.builder()
                .taskId("task-123")
                .status(ExtractionStatus.COMPLETED)
                .found(true)
                .type("INVOICE")
                .rawResponse('{"documentNumber": "INV-001"}')
                .build()
            processingService.getExtractTask("task-123") >> result

        and: "extraction parsing succeeds"
            documentExtractor.extract(_) >> '{"documentNumber": "INV-001"}'

        and: "no PO found"
            poService.getPoWithItemsByPoNumber(_, _) >> Optional.empty()

        and: "transaction succeeds"
            def txContext = Mock(org.jooq.Configuration)
            txContext.dsl() >> dslContext
            dslContext.transactionResult(_) >> { args ->
                def lambda = args[0] as org.jooq.TransactionalCallable
                lambda.run(txContext)
            }

        when: "extracting"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "task is updated without PO match"
            1 * extractionTaskRepository.update(dslContext, { ExtractionTask t ->
                t.status == ExtractionStatus.COMPLETED &&
                t.matchType == MatchType.NO_MATCH &&
                t.purchaseOrderId == null
            }, _)
            0 * comparisonRepository.upsert(_, _, _)
            1 * documentApprovalRepository.createInitialApproval(dslContext, null, "INV-001", "attach-123", _, _, _)
    }

    def "processTaskResult should handle not found result by starting new task"() {
        given: "email metadata exists"
            def emailMetadata = createEmailMetadata()
            emailMetadataService.getEmailMetadataByAttachmentId("attach-123") >> Optional.of(emailMetadata)

        and: "task"
            def task = createTask("attach-123").toBuilder()
                .status(ExtractionStatus.STARTED)
                .taskId("task-123")
                .preparationId("prep-123")
                .build()
            extractionTaskRepository.save(_) >> task

        and: "task not found in provider"
            processingService.getExtractTask("task-123") >>
                ExtractTaskResult.builder()
                    .found(false)
                    .error("Task not found")
                    .throwable(new TaskNotFoundException("Task not found"))
                    .build()

        and: "new task start succeeds"
            jsonSchemaLoader.loadSchema() >> "{}"
            promptLoader.loadPrompt() >> "Extract data"
            processingService.startTask(_) >> StartTaskResult.success("new-task-456")
            extractionTaskRepository.update(_ as ExtractionTask, _ as ExtractionStatus) >> task

        when: "checking completion"
            service.extract("attach-123", "s3://bucket/file.pdf")

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== FIND BY TASK ID TESTS ====================

    def "findByTaskId should delegate to repository"() {
        given: "repository returns task"
            def task = createTask("attach-123")
            extractionTaskRepository.findByTaskId("task-123") >> Optional.of(task)

        when: "finding by task ID"
            def result = service.findByTaskId("task-123")

        then: "task is returned"
            result.isPresent()
            result.get().assignedId == "attach-123"
    }

    def "findByTaskId should return empty when not found"() {
        given: "repository returns empty"
            extractionTaskRepository.findByTaskId("missing") >> Optional.empty()

        when: "finding by task ID"
            def result = service.findByTaskId("missing")

        then: "empty is returned"
            result.isEmpty()
    }

    // ==================== HELPER METHODS ====================

    private ExtractionTask createTask(String assignedId) {
        return ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(1L)
            .storageKey("s3://bucket/file.pdf")
            .fromAddress("sender@example.com")
            .toAddress("receiver@example.com")
            .emailSubject("Test Subject")
            .emailMessageId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .emailThreadId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .status(ExtractionStatus.PENDING)
            .receivedAt(OffsetDateTime.now())
            .createdAt(OffsetDateTime.now())
            .build()
    }

    private EmailMetadata createEmailMetadata() {
        return EmailMetadata.builder()
            .companyId(1L)
            .fromAddress("sender@example.com")
            .toAddress("receiver@example.com")
            .subject("Test Subject")
            .emailMessageId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .emailThreadId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .receivedAt(OffsetDateTime.now())
            .build()
    }
}
