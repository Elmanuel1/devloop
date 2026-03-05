package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Inbound Reducto webhook payload — {@code job_id} and {@code status} ("Completed" or "Failed"). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReductoWebhookPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status
) {}
