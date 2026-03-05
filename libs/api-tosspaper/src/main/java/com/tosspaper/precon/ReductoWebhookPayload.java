package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson DTO representing the inbound payload from a Reducto webhook callback.
 *
 * <p>Reducto delivers this body via Svix when an asynchronous job completes or
 * fails. The payload is intentionally minimal — Reducto does not include the
 * full result in the webhook body. Full results must be retrieved separately by
 * calling the Reducto jobs API with the {@code job_id}.
 *
 * <p>Payload shape (from Reducto documentation):
 * <pre>{@code
 * {
 *   "status":   "Completed" | "Failed",
 *   "job_id":   "a1b9090e-c9ae-420b-9726-f658afbbe338",
 *   "metadata": { ... }
 * }
 * }</pre>
 *
 * <p>Unknown fields are ignored to allow forward-compatible evolution of the
 * Reducto schema.
 *
 * @param jobId   the Reducto job identifier — correlates with
 *                {@code extractions.external_task_id}
 * @param status  {@code "Completed"} or {@code "Failed"} (Reducto uses title-case)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReductoWebhookPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status
) {}
