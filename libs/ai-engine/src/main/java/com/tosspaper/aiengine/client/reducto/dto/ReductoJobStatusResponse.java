package com.tosspaper.aiengine.client.reducto.dto;

import com.tosspaper.models.domain.ExtractionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoJobStatusResponse {
    private String status; // Pending, Completed, Failed, Idle
    private Object result; // Can be ParseResponse, ExtractResponse, etc.
    private Double progress;
    private String reason;
    private String type; // Parse, Extract, Split, Edit, Pipeline
    private Integer numPages;
    private Integer totalPages;
    private Object source;
    private Double duration;
    private String createdAt;
    private String rawConfig;
    private Object bucket;
    private String rawResponse;
    private String documentType; // Document type extracted from result object
    /**
     * Map Reducto status to internal ExtractionStatus enum.
     * 
     * @return mapped internal status
     */
    public ExtractionStatus mapToInternalStatus() {
        return switch (status.toLowerCase()) {
            case "completed" -> ExtractionStatus.COMPLETED;
            case "failed" -> ExtractionStatus.FAILED;
            case "pending" -> ExtractionStatus.STARTED;
            case "idle" -> ExtractionStatus.PENDING;
            default -> ExtractionStatus.MANUAL_INTERVENTION;
        };
    }
}
