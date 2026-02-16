package com.tosspaper.aiengine.client.reducto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonProperty("num_pages")
    private Integer numPages;
    @JsonProperty("total_pages")
    private Integer totalPages;
    private Object source;
    private Double duration;
    @JsonProperty("created_at")
    private String createdAt;
    @JsonProperty("raw_config")
    private String rawConfig;
    private Object bucket;
    @JsonProperty("raw_response")
    private String rawResponse;
    @JsonProperty("document_type")
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
