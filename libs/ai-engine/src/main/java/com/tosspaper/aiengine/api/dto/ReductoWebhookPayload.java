package com.tosspaper.aiengine.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Webhook payload from Reducto.
 */
@Data
public class ReductoWebhookPayload {
    @JsonProperty("job_id")
    private String jobId;
    private String status;
    private Map<String, String> metadata;
}
