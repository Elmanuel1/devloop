package com.tosspaper.aiengine.client.reducto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Reducto's async extract endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoAsyncExtractResponse {
    @JsonProperty("job_id")
    private String jobId;
}
