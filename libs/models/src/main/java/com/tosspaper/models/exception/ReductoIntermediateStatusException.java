package com.tosspaper.models.exception;

import lombok.Getter;

/**
 * Signals that a Reducto checkback call returned a non-terminal status:
 * {@code PENDING} or {@code PROCESSING}.
 *
 * <p>This exception is <strong>not a failure</strong>. It signals that the
 * Reducto task is still in-flight at the remote service. Callers should
 * <em>not</em> mark the extraction as {@code FAILED}. Instead, release the
 * per-extraction lock and allow the next poll cycle to retry.
 *
 * <p>Contrast with {@code ReductoTaskException}, which signals a hard error
 * returned by the Reducto API and <em>should</em> mark the extraction FAILED.
 */
@Getter
public class ReductoIntermediateStatusException extends RuntimeException {

    private final String status;

    /**
     * @param status the non-terminal status name returned by Reducto
     *               (typically {@code "PENDING"} or {@code "PROCESSING"})
     */
    public ReductoIntermediateStatusException(String status) {
        super(ApiErrorMessages.REDUCTO_INTERMEDIATE_STATUS.formatted(status));
        this.status = status;
    }
}
