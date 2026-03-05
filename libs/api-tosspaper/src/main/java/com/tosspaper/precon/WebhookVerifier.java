package com.tosspaper.precon;

import com.svix.exceptions.WebhookVerificationException;

import java.net.http.HttpHeaders;

/**
 * Abstraction over Svix webhook signature verification.
 *
 * <p>The {@link com.svix.Webhook} class is {@code final} and cannot be mocked in
 * standard unit tests. This interface wraps the verification call, enabling the
 * {@link ReductoWebhookController} to be tested without a real Svix secret or valid
 * signatures.
 *
 * <p>The production implementation delegates to {@link com.svix.Webhook#verify}.
 */
@FunctionalInterface
public interface WebhookVerifier {

    /**
     * Verifies the Svix signature for an inbound webhook.
     *
     * @param payload     the raw request body string
     * @param svixHeaders the Svix headers ({@code svix-id}, {@code svix-timestamp},
     *                    {@code svix-signature})
     * @throws WebhookVerificationException if the signature is invalid or headers are missing
     */
    void verify(String payload, HttpHeaders svixHeaders) throws WebhookVerificationException;
}
