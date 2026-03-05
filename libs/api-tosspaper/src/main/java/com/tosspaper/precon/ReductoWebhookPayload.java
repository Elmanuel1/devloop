package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Jackson DTO representing the inbound payload from a Reducto webhook callback.
 *
 * <p>Reducto delivers this body when an asynchronous extraction task completes
 * or fails. Unknown fields are ignored to allow forward-compatible evolution of
 * the Reducto schema.
 *
 * @param taskId  the opaque task identifier that correlates with
 *                {@code extractions.external_task_id}
 * @param status  {@code "completed"} or {@code "failed"}
 * @param result  structured extraction result (non-null when {@code status == "completed"})
 * @param error   human-readable error description (non-null when {@code status == "failed"})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReductoWebhookPayload(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("status")  String status,
        @JsonProperty("result")  JsonNode result,
        @JsonProperty("error")   String error
) {}
