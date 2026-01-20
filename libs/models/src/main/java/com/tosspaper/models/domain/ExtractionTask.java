package com.tosspaper.models.domain;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Domain model for extraction tasks.
 * Represents an AI extraction job for an email attachment.
 */
@Data
@Builder(toBuilder = true)
public class ExtractionTask {
    
    /**
     * Assigned ID of the email attachment (primary key).
     */
    private String assignedId;
    
    /**
     * Company ID that owns this extraction task.
     */
    private Long companyId;
    
    /**
     * Storage key for the file to be processed.
     */
    private String storageKey;
    
    /**
     * Current status of the extraction task.
     */
    private ExtractionStatus status;
    
    /**
     * Provider-specific ID from file preparation step.
     */
    private String preparationId;
    
    /**
     * Provider-specific task ID for the extraction job.
     */
    private String taskId;
    
    /**
     * Error message encountered during processing.
     */
    private String errorMessage;
    
    /**
     * Number of execution attempts for this task.
     */
    private Integer attempts;
    
    /**
     * When the task was created.
     */
    private OffsetDateTime createdAt;
    
    /**
     * When the task was last updated.
     */
    private OffsetDateTime updatedAt;

    private List<PageTask> pages;
    
    /**
     * When the preparation phase started.
     */
    private OffsetDateTime preparationStartedAt;
    
    /**
     * When the extraction phase started.
     */
    private OffsetDateTime extractionStartedAt;
    
    /**
     * Classified document type for conformance processing.
     * Extracted from provider response (e.g., Reducto).
     */
    private DocumentType documentType;
    
    /**
     * Full raw extraction results from provider (stored for reference).
     * Cleaned and chunked version stored in vector database.
     */
    private String extractTaskResults;
    
    /**
     * AI-conformed JSON matching schema with quality validation.
     */
    private String conformedJson;
    
    /**
     * Quality score from AI evaluation (0.0 to 1.0) - denormalized for fast queries.
     */
    private Double conformanceScore;
    
    /**
     * Conformance processing status.
     */
    private ConformanceStatus conformanceStatus;
    
    /**
     * Number of conformance retry attempts.
     */
    private Integer conformanceAttempts;
    
    /**
     * Array of attempt summaries: [{attempt: 1, score: 0.72, issues: [...]}, ...]
     */
    private String conformanceHistory;
    
    /**
     * Full final EvaluationResponse from AI including score, issues, correctedJson, and suggestions for review.
     */
    private String conformanceEvaluation;
    
    /**
     * Timestamp when conformance completed.
     */
    private OffsetDateTime conformedAt;
    
    /**
     * Email sender address.
     */
    private String fromAddress;
    
    /**
     * Email recipient address.
     */
    private String toAddress;
    
    /**
     * Email direction (incoming/outgoing).
     */
    private String emailDirection;
    
    /**
     * Email subject.
     */
    private String emailSubject;

    /**
     * Reference to the originating email message ID.
     */
    private UUID emailMessageId;

    /**
     * Reference to the originating email thread ID.
     */
    private UUID emailThreadId;

    /**
     * Timestamp when the email was received from the provider.
     */
    private OffsetDateTime receivedAt;

    // Denormalized match fields
    private MatchType matchType;
    private String matchReport;
    private String reviewStatus;
    private String projectId;
    private String purchaseOrderId;
    private String poNumber;
}
