package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Default {@link ReductoClient} that calls the Reducto REST API over HTTP.
 *
 * <h3>One call per document</h3>
 * <p>Reducto has no batch endpoint. This client submits one document per call and
 * includes a {@code webhook_url} in the request body so Reducto can push the result
 * back asynchronously when extraction is complete.
 *
 * <h3>Error handling</h3>
 * <p>Any non-2xx response or I/O failure throws {@link ReductoClientException}.
 * The caller (ExtractionWorker) catches this and marks the document as failed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpReductoClient implements ReductoClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final ReductoProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Override
    public ReductoSubmitResponse submit(ReductoSubmitRequest request) {
        log.debug("[ReductoClient] Submitting document '{}' for extraction '{}'",
                request.documentId(), request.extractionId());

        String body = buildRequestBody(request);
        HttpRequest httpRequest = buildHttpRequest(body);

        HttpResponse<String> response = executeRequest(request, httpRequest);
        return parseResponse(request, response);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildRequestBody(ReductoSubmitRequest request) {
        try {
            return objectMapper.writeValueAsString(new ReductoApiPayload(
                    request.s3Key(),
                    request.webhookUrl(),
                    request.extractionId(),
                    request.documentId()
            ));
        } catch (IOException e) {
            throw new ReductoClientException(
                    ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                            request.documentId(), request.extractionId()),
                    e);
        }
    }

    private HttpRequest buildHttpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/extract"))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Authorization", "Bearer " + props.getApiKey())
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> executeRequest(ReductoSubmitRequest request, HttpRequest httpRequest) {
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ReductoClientException(
                    ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                            request.documentId(), request.extractionId()),
                    e);
        }
    }

    private ReductoSubmitResponse parseResponse(ReductoSubmitRequest request,
                                                 HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            log.error("[ReductoClient] Non-2xx response {} for document '{}': {}",
                    status, request.documentId(), response.body());
            throw new ReductoClientException(
                    ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                            request.documentId(), request.extractionId()));
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            String taskId = root.path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                                request.documentId(), request.extractionId()));
            }
            log.debug("[ReductoClient] Document '{}' submitted — taskId='{}'", request.documentId(), taskId);
            return new ReductoSubmitResponse(taskId);
        } catch (IOException e) {
            throw new ReductoClientException(
                    ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                            request.documentId(), request.extractionId()),
                    e);
        }
    }

    // ── Internal DTO for serialisation only ───────────────────────────────────

    @SuppressWarnings("unused")  // fields read by Jackson
    private record ReductoApiPayload(
            String s3_key,
            String webhook_url,
            String extraction_id,
            String document_id
    ) {}
}
