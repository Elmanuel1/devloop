package com.tosspaper.aiengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.client.common.dto.PreparationResult;
import com.tosspaper.aiengine.client.common.dto.StartTaskResult;
import com.tosspaper.aiengine.dto.StartTaskRequest;
import com.tosspaper.aiengine.extractors.DocumentExtractor;
import com.tosspaper.aiengine.loaders.JsonSchemaLoader;
import com.tosspaper.aiengine.loaders.PromptLoader;
import com.tosspaper.aiengine.repository.DocumentApprovalRepository;
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.*;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.DeliveryTransaction;
import com.tosspaper.models.extraction.dto.Extraction;
import com.tosspaper.models.messaging.MessagePublisher;
import com.tosspaper.models.service.*;
import com.tosspaper.models.storage.DownloadResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;


/**
 * Service for orchestrating extraction tasks.
 * Handles the complete lifecycle of AI extraction jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionService {

    private final ExtractionTaskRepository extractionTaskRepository;
    private final ProcessingService processingService;
    private final JsonSchemaLoader jsonSchemaLoader;
    private final PromptLoader promptLoader;
    private final StorageService s3StorageService;
    private final MessagePublisher publisher;
    private final EmailMetadataService emailMetadataService;
    private final DocumentExtractor documentExtractor;
    private final DocumentApprovalRepository documentApprovalRepository;
    private final ObjectMapper objectMapper;
    private final DSLContext dslContext;
    private final PurchaseOrderLookupService poService;
    private final DocumentPartComparisonService comparisonService;
    private final DocumentPartComparisonRepository comparisonRepository;
    private final ObservationRegistry observationRegistry;

    /**
     * Start an extraction task.
     *
     * @param assignedId the assigned ID of the email attachment
     * @param storageKey the storage key of the file to process
     */
    @Observed(
        name = "document.extract",
        contextualName = "Document Extraction",
        lowCardinalityKeyValues = {"service", "extraction", "operation", "extract"}
    )
    public void extract(String assignedId, String storageKey) {
        // Add dynamic span attributes only if sampled (to reduce cost)
        Observation observation = observationRegistry.getCurrentObservation();
        if (observation != null && assignedId != null && !observation.isNoop()) {
            observation.highCardinalityKeyValue("assignedId", assignedId);
        }

        log.info("Starting extraction task for assigned ID: {}, storage key: {}", assignedId, storageKey);

        // Fetch email metadata by attachment assigned_id
        EmailMetadata emailMetadata = emailMetadataService.getEmailMetadataByAttachmentId(assignedId)
            .orElseThrow(() -> new RuntimeException("Email message not found for attachment: " + assignedId));

        // Create a task record
        ExtractionTask task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(emailMetadata.getCompanyId())
            .storageKey(storageKey)
            .fromAddress(emailMetadata.getFromAddress())
            .toAddress(emailMetadata.getToAddress())
            .emailSubject(emailMetadata.getSubject())
            .emailMessageId(emailMetadata.getEmailMessageId())
            .emailThreadId(emailMetadata.getEmailThreadId())
            .receivedAt(emailMetadata.getReceivedAt())
            .createdAt(OffsetDateTime.now())
            .build();

        // Save/update task (will increment attempts if exists, also it's an idempotent method wll return old value if exists)
        ExtractionTask savedTask = extractionTaskRepository.save(task);
        log.info("Saved extraction task: {} with status: {} and attempts: {}",
                savedTask.getAssignedId(), savedTask.getStatus(), savedTask.getAttempts());

        // Switch on status to determine the next action
        switch (savedTask.getStatus()) {
            case COMPLETED, CANCELLED -> log.info("Task {} already completed", savedTask.getAssignedId());

            case MANUAL_INTERVENTION -> log.info("Task {} requires manual intervention", savedTask.getAssignedId());

            case PREPARE_FAILED, PENDING -> {
                log.info("Task {} preparation failed, starting fresh", savedTask.getAssignedId());
                downloadAndStartExecution(savedTask, storageKey);
            }

            case PREPARE_SUCCEEDED, START_TASK_FAILED -> {
                log.info("Task {} trying to start extraction, status: {}", savedTask.getAssignedId(), savedTask.getStatus());
                startExtraction(savedTask);
            }

            case PREPARE_UNKNOWN -> {
                log.info("Task {} preparation status unknown, checking preparation to start execution", savedTask.getAssignedId());
                checkPreparationToStartExecution(savedTask, storageKey);
            }


            case STARTED -> {
                log.info("Task {} is started, checking completion status", savedTask.getAssignedId());
                checkCompletionStatus(savedTask);
            }

            case START_TASK_UNKNOWN -> {
                log.info("Task {} failed to start with unknown error, no retry", savedTask.getAssignedId());
                checkCompletionStatus(savedTask);
            }

            case FAILED -> log.info("Task {} failed, no retry", savedTask.getAssignedId());

        }
    }

    @SneakyThrows
    private void downloadAndStartExecution(ExtractionTask task, String storageKey) {
        log.info("Downloading from S3 and starting execution from scratch: {}", task.getAssignedId());

        var existingStatus = task.getStatus();
        var preparationStartTime = OffsetDateTime.now();

        // Step 1: Download a file from S3
        log.info("Downloading file from S3: {}", storageKey);
        DownloadResult downloadResult = s3StorageService.download(storageKey);

        if (downloadResult.isFailed()) {
            log.error("S3 download failed for task: {} - {}", task.getAssignedId(), downloadResult.getError());
            task = task.toBuilder()
                .status(com.tosspaper.models.domain.ExtractionStatus.PREPARE_FAILED)
                .errorMessage("S3 download failed: " + downloadResult.getError())
                .preparationStartedAt(preparationStartTime)
                .build();
            extractionTaskRepository.update(task, existingStatus);

            return;
        }

        // Step 2: Upload to provider
        var preparationEndTime = OffsetDateTime.now();

        // Set the assignedId on the FileObject for proper logging
        com.tosspaper.models.domain.FileObject fileObject = downloadResult.getFileObject()
            .toBuilder()
            .assignedId(task.getAssignedId())
            .build();

        PreparationResult preparationResult = processingService.prepareTask(fileObject);

        if (preparationResult.isFailed()) {
            log.error("Preparation failed for task: {} - {}", task.getAssignedId(), preparationResult.getError());

            task = task.toBuilder()
                .status(preparationResult.getStatus())
                .errorMessage("Preparation failed: " + preparationResult.getError())
                .preparationStartedAt(preparationStartTime)
                .build();
            extractionTaskRepository.update(task, existingStatus);

            return;
        }

        // Step 3: Preparation successful - update task with preparation ID and timing
        task = task.toBuilder()
            .preparationId(preparationResult.getPreparationId())
            .status(preparationResult.getStatus())
            .preparationStartedAt(preparationStartTime)
            .extractionStartedAt(preparationEndTime)
            .build();
        extractionTaskRepository.update(task, existingStatus);

        // Step 4: Start extraction
        startExtraction(task);
    }

    @SneakyThrows
    private void checkPreparationToStartExecution(ExtractionTask task, String storageKey) {
        log.info("Checking if file exists for task: {}", task.getAssignedId());

        // Search for the file using the new search method
        var fileResult = processingService.searchExecutionFile(task);

        if (fileResult.isFound()) {
            log.info("File already exists in provider for task: {} (fileId: {}), preparing and starting",
                task.getAssignedId(), fileResult.getFileId());
            // File exists, prepare and start the task
            startExtraction(task);
        } else if (fileResult.isNotFound()) {
            log.info("File not found in provider for task: {} - {}, downloading and starting",
                task.getAssignedId(), fileResult.getError());
            downloadAndStartExecution(task, storageKey);
        } else {
            log.warn("File search failed for task: {}",
                task.getAssignedId(), fileResult.getThrowable());
            throw fileResult.getThrowable();
        }
    }

    @SneakyThrows
    private void startExtraction(ExtractionTask task) {
        log.info("Starting extraction for task: {}", task.getAssignedId());

        var existingStatus = task.getStatus();
        var extractionStartTime = task.getExtractionStartedAt() != null ?
            task.getExtractionStartedAt() : OffsetDateTime.now();

        // Load schema and prompt
        String schema = jsonSchemaLoader.loadSchema();
        String prompt = promptLoader.loadPrompt();

        // Start an extraction task
        StartTaskRequest startRequest = StartTaskRequest.builder()
            .preparationId(task.getPreparationId())
            .schema(schema)
            .prompt(prompt)
            .assignedId(task.getAssignedId())
            .build();

        StartTaskResult startResult = processingService.startTask(startRequest);

        if (startResult.isFailed()) {
            log.error("Failed to start extraction task: {} - {}", task.getAssignedId(), startResult.getError());

            // Update task with failure status
            task = task.toBuilder()
                .status(startResult.toExtractionStatus())
                .errorMessage("Failed to start extraction: " + startResult.getError())
                .extractionStartedAt(extractionStartTime)
                .taskId(task.getTaskId())
                .build();
            extractionTaskRepository.update(task, existingStatus);

            return;
        }

        // Update task with task ID and timing
        task = task.toBuilder()
            .taskId(startResult.getTaskId())
            .status(ExtractionStatus.STARTED)
            .extractionStartedAt(extractionStartTime)
            .build();

        extractionTaskRepository.update(task, existingStatus);

        log.info("Successfully started extraction task: {} (task: {})", task.getAssignedId(), startResult.getTaskId());
    }

    private void checkCompletionStatus(ExtractionTask task) {
        log.info("Checking completion status for task: {}, status: {}, external task id {}", task.getAssignedId(), task.getStatus(),  task.getTaskId());

        // If we have a task ID, try direct lookup first
        var result =  Optional.ofNullable(task.getTaskId())
                .map(processingService::getExtractTask)
                .orElseGet(() -> processingService.searchExecutionTask(task));
        processTaskResult(task, result);
    }

    /**
     * Process task result from either direct lookup or search.
     * Handles the common logic for updating task status and saving results.
     *
     * @param task       the original task
     * @param taskResult the result from a provider
     */
    @SneakyThrows
    private void processTaskResult(ExtractionTask task, ExtractTaskResult taskResult) {
        if (taskResult.isFound()) {
            log.info("Found task {} with status: {}", taskResult.getTaskId(), taskResult.getStatus());

            // Parse document type from result
            DocumentType docType = DocumentType.fromString(taskResult.getType());

            // 1. Strip citations from Reducto response
            String cleanedJson = documentExtractor.extract(taskResult.getRawResponse());
            Extraction extraction = objectMapper.readValue(cleanedJson, Extraction.class);

            // Build a task with raw extraction results and document type
            var newTask = task.toBuilder()
                    .status(taskResult.getStatus())
                    .taskId(taskResult.getTaskId())
                    .documentType(docType)
                    .extractTaskResults(taskResult.getRawResponse())
                    .conformedJson(cleanedJson)
                    .matchType(MatchType.NO_MATCH)
                    .build();

            var po = Optional.ofNullable(extraction.getCustomerPONumber())
                    .or(() -> Optional.ofNullable(extraction.getDeliveryTransactions())
                            .flatMap(txns -> txns.stream().findFirst())
                            .map(DeliveryTransaction::getPoNumber))
                    .flatMap(poNum -> poService.getPoWithItemsByPoNumber(task.getCompanyId(), poNum));

            Comparison comparison = null;

            if (po.isPresent()) {
                PurchaseOrder p = po.get();
                newTask.setPurchaseOrderId(p.getId());
                newTask.setPoNumber(p.getDisplayId());
                newTask.setMatchType(MatchType.DIRECT);
                newTask.setProjectId(p.getProjectId());
                var comparisonContext = new ComparisonContext(p, newTask);
                comparison = comparisonService.compareDocumentParts(comparisonContext);
            }

            // Capture comparison for use in transaction lambda
            final Comparison finalComparison = comparison;

            boolean succeeded = dslContext.transactionResult(ctx -> {
                log.info("Updating extraction task {} with result from provider", newTask.getAssignedId());
                extractionTaskRepository.update(ctx.dsl(), newTask, task.getStatus());

                if (finalComparison != null) {
                    log.info("Saving comparison for {}", newTask.getAssignedId());
                    comparisonRepository.upsert(ctx.dsl(), newTask.getAssignedId(), finalComparison);
                }

                // Process extracted document if extraction succeeded
                if (taskResult.getStatus() == ExtractionStatus.COMPLETED) {
                    log.info("Creating pending approval record for {}", newTask.getTaskId());
                    documentApprovalRepository.createInitialApproval(ctx.dsl(),
                            extraction.getCustomerPONumber(),
                            extraction.getDocumentNumber(),
                            newTask.getAssignedId(),
                            newTask.getCompanyId(),
                            newTask.getFromAddress(),
                            docType);
                }

                return true;
            });

            if (succeeded) {
                publisher.publish("po-match-requests", Map.of("assignedId", task.getAssignedId()));
            }
            
            log.info("Task {} updated with status: {}, document type: {}", 
                newTask.getAssignedId(), newTask.getStatus(), docType);

        } else if (taskResult.isNotFound()) {
            log.warn("Task not found for {}: {}, starting new task", task.getAssignedId(), taskResult.getError());

            // Task not found - start a new task
            startExtraction(task);
        } else {
            throw taskResult.getThrowable();
        }
    }

    public Optional<ExtractionTask> findByTaskId(String taskId) {
        return extractionTaskRepository.findByTaskId(taskId);
    }
}
