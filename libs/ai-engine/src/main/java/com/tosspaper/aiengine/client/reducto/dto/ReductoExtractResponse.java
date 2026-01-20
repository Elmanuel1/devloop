package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from Reducto extract endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoExtractResponse {
    private String jobId;
    private ReductoUsage usage;
    private String studioLink;
    private Object result;  // Dynamic JSON result
    private Object citations;
}
