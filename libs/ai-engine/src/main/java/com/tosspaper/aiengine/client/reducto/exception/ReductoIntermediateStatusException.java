package com.tosspaper.aiengine.client.reducto.exception;

import com.tosspaper.aiengine.client.reducto.dto.ReductoStatus;

/**
 * Signals that a Reducto checkback call returned a non-terminal status:
 * {@link ReductoStatus#PENDING} or {@link ReductoStatus#PROCESSING}.
 *
 * <p>This exception is <strong>not a failure</strong>. It signals that the
 * Reducto task is still in-flight at the remote service. Callers should
 * <em>not</em> mark the extraction as {@code FAILED}. Instead, release the
 * per-extraction lock and allow the next poll cycle to retry.
 *
 * <p>Contrast with {@link ReductoTaskException}, which signals a hard error
 * returned by the Reducto API and <em>should</em> mark the extraction FAILED.
 */
public class ReductoIntermediateStatusException extends ReductoException {

    private final ReductoStatus status;

    /**
     * @param status the non-terminal status returned by Reducto
     *               (typically {@code PENDING} or {@code PROCESSING})
     */
    public ReductoIntermediateStatusException(ReductoStatus status) {
        super("Reducto task is not yet complete — current status: " + status);
        this.status = status;
    }

    /**
     * @return the non-terminal status that triggered this exception
     */
    public ReductoStatus getStatus() {
        return status;
    }
}
