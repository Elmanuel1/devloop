package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.loaders.JsonSchemaLoader;
import com.tosspaper.aiengine.loaders.PromptLoader;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.exception.ReductoClientException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Calls the Reducto REST API to upload and start async extraction. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpReductoClient implements ExtractionClient {

    private static final String UPLOAD_PATH   = "/upload";
    private static final String EXTRACT_PATH  = "/extract";
    private static final String JOB_PATH      = "/job/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ReductoProperties props;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final JsonSchemaLoader schemaLoader;
    private final PromptLoader promptLoader;

    @Override
    @SneakyThrows
    public ExtractionSubmitResponse submit(ExtractionSubmitRequest request) {
        log.debug("[ReductoClient] Submitting document '{}' for extraction '{}'",
                request.documentId(), request.extractionId());

        String fileId = resolveFileId(request);
        return startExtraction(request, fileId);
    }

    @Override
    @SneakyThrows
    public ExtractionTaskResult getTask(String taskId) {
        log.debug("[ReductoClient] Fetching task result for taskId='{}'", taskId);

        Request httpRequest = new Request.Builder()
                .url(props.getBaseUrl() + JOB_PATH + taskId)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .get()
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_HTTP_ERROR.formatted(
                                response.code(), taskId, "getTask", body));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String status = root.path("status").asText(null);
            if (status == null || status.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_MALFORMED_RESPONSE.formatted(
                                taskId, "getTask", "status"));
            }
            String reason = root.path("reason").asText(null);
            JsonNode result = root.path("result");
            log.debug("[ReductoClient] Task '{}' status='{}'", taskId, status);
            return new ExtractionTaskResult(taskId, status, reason, result.isMissingNode() ? null : result);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the existing fileId if already uploaded, otherwise uploads the file. */
    private String resolveFileId(ExtractionSubmitRequest request) {
        if (request.externalFileId() != null && !request.externalFileId().isBlank()) {
            log.debug("[ReductoClient] Document '{}' — reusing existing fileId='{}'",
                    request.documentId(), request.externalFileId());
            return request.externalFileId();
        }
        return uploadFile(request);
    }

    @SneakyThrows
    private String uploadFile(ExtractionSubmitRequest request) {
        RequestBody body = RequestBody.create(request.fileBytes());
        Request httpRequest = new Request.Builder()
                .url(props.getBaseUrl() + UPLOAD_PATH)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("[ReductoClient] Upload HTTP {} for document '{}': {}",
                        response.code(), request.documentId(), errorBody);
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_HTTP_ERROR.formatted(
                                response.code(), request.documentId(), request.extractionId(), errorBody));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String fileId = root.path("file_id").asText(null);
            if (fileId == null || fileId.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_MALFORMED_RESPONSE.formatted(
                                request.documentId(), request.extractionId(), "file_id"));
            }
            log.debug("[ReductoClient] Document '{}' uploaded — fileId='{}'", request.documentId(), fileId);
            return fileId;
        }
    }

    @SneakyThrows
    private ExtractionSubmitResponse startExtraction(ExtractionSubmitRequest request, String fileId) {
        String schema = schemaLoader.loadSchema("extraction");
        String systemPrompt = promptLoader.loadPrompt("extraction");

        String bodyJson = objectMapper.writeValueAsString(new ReductoExtractPayload(
                fileId,
                request.extractionId(),
                request.documentId(),
                request.documentType().name().toLowerCase(java.util.Locale.ROOT),
                new ReductoWebhookConfig("svix", new String[]{props.getSvixChannel()}),
                schema,
                systemPrompt
        ));
        Request httpRequest = new Request.Builder()
                .url(props.getBaseUrl() + EXTRACT_PATH)
                .addHeader("Authorization", "Bearer " + props.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("[ReductoClient] Extract HTTP {} for document '{}': {}",
                        response.code(), request.documentId(), errorBody);
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_HTTP_ERROR.formatted(
                                response.code(), request.documentId(), request.extractionId(), errorBody));
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String taskId = root.path("task_id").asText(null);
            if (taskId == null || taskId.isBlank()) {
                throw new ReductoClientException(
                        ApiErrorMessages.REDUCTO_MALFORMED_RESPONSE.formatted(
                                request.documentId(), request.extractionId(), "task_id"));
            }
            log.debug("[ReductoClient] Document '{}' extraction started — taskId='{}'", request.documentId(), taskId);
            return new ExtractionSubmitResponse(taskId, fileId);
        }
    }

    @SuppressWarnings("unused")
    private record ReductoExtractPayload(
            String file_id,
            String extraction_id,
            String document_id,
            String document_type,
            ReductoWebhookConfig webhook,
            String schema,
            String system_prompt
    ) {}

    @SuppressWarnings("unused")
    private record ReductoWebhookConfig(
            String mode,
            String[] channels
    ) {}
}
