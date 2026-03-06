package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.exception.ReductoClientException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

/**
 * Calls the Reducto REST API using the two-step upload → extract flow.
 * Step 1: upload document bytes to {@code /upload} to obtain a {@code file_id}.
 * Step 2: call {@code /extract} with the {@code file_id} to start async extraction and receive a {@code task_id}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpReductoClient implements ReductoClient {

    private static final String UPLOAD_PATH = "/upload";
    private static final String EXTRACT_PATH = "/extract";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ReductoProperties props;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    @Override
    @SneakyThrows
    public ReductoSubmitResponse submit(ReductoSubmitRequest request) {
        log.debug("[ReductoClient] Submitting document '{}' for extraction '{}'",
                request.documentId(), request.extractionId());

        String fileId = uploadFile(request);
        return startExtraction(request, fileId);
    }

    @SneakyThrows
    private String uploadFile(ReductoSubmitRequest request) {
        RequestBody body = RequestBody.create(request.fileBytes());
        Request httpRequest = new Request.Builder()
                .url(props.getBaseUrl() + UPLOAD_PATH)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.error("[ReductoClient] Upload failed {} for document '{}': {}",
                        response.code(), request.documentId(), response.body() != null ? response.body().string() : "");
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                                request.documentId(), request.extractionId()));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String fileId = root.path("file_id").asText(null);
            if (fileId == null || fileId.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                                request.documentId(), request.extractionId()));
            }
            log.debug("[ReductoClient] Document '{}' uploaded — fileId='{}'", request.documentId(), fileId);
            return fileId;
        }
    }

    @SneakyThrows
    private ReductoSubmitResponse startExtraction(ReductoSubmitRequest request, String fileId) {
        String bodyJson = objectMapper.writeValueAsString(new ReductoExtractPayload(
                fileId,
                request.webhookUrl(),
                request.extractionId(),
                request.documentId(),
                request.documentType().name().toLowerCase(java.util.Locale.ROOT)
        ));
        Request httpRequest = new Request.Builder()
                .url(props.getBaseUrl() + EXTRACT_PATH)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.error("[ReductoClient] Extract failed {} for document '{}': {}",
                        response.code(), request.documentId(), response.body() != null ? response.body().string() : "");
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                                request.documentId(), request.extractionId()));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String taskId = root.path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_SUBMIT_FAILED.formatted(
                                request.documentId(), request.extractionId()));
            }
            log.debug("[ReductoClient] Document '{}' extraction started — taskId='{}'", request.documentId(), taskId);
            return new ReductoSubmitResponse(taskId, fileId);
        }
    }

    @SuppressWarnings("unused")
    private record ReductoExtractPayload(
            String file_id,
            String webhook_url,
            String extraction_id,
            String document_id,
            String document_type
    ) {}
}
