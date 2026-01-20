package com.tosspaper.aiengine.client.reducto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.common.dto.Citations;
import com.tosspaper.aiengine.client.common.dto.Instructions;
import com.tosspaper.aiengine.client.common.dto.Settings;
import com.tosspaper.aiengine.client.reducto.dto.*;
import com.tosspaper.aiengine.client.common.exception.StartTaskException;
import com.tosspaper.aiengine.client.reducto.exception.ReductoTaskException;
import com.tosspaper.aiengine.client.reducto.exception.ReductoUploadException;
import com.tosspaper.models.domain.FileObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * HTTP client for Reducto AI API.
 * Provides methods for file upload via presigned URL and extract task creation/retrieval.
 * Based on https://platform.reducto.ai/
 */
@Slf4j
@RequiredArgsConstructor
public class ReductoClient {
    
    private static final String BASE_URL = "https://platform.reducto.ai";
    private static final String UPLOAD_ENDPOINT = "/upload";
    private static final String EXTRACT_ASYNC_ENDPOINT = "/extract_async";
    
    // HTTP Header constants
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    /**
     * Request a presigned URL for file upload.
     * 
     * @return ReductoUploadResponse with file_id and presigned_url
     * @throws IOException if the request fails
     */
    public ReductoUploadResponse requestPresignedUrl() throws IOException {
        log.info("Requesting presigned URL from Reducto");
        
        Request request = new Request.Builder()
            .url(BASE_URL + UPLOAD_ENDPOINT)
            .addHeader(HEADER_AUTHORIZATION, "Bearer " + apiKey)
            .post(RequestBody.create("", MediaType.get(CONTENT_TYPE_JSON)))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Reducto upload request failed with status {}: {}", response.code(), errorBody);
                throw new ReductoUploadException("Failed to request presigned URL: " + response.code() + " - " + errorBody);
            }
            
            String responseBody = response.body().string();
            log.info("Raw response body from Reducto upload endpoint: {}", responseBody);
            
            ReductoUploadResponse uploadResponse = objectMapper.readValue(responseBody, ReductoUploadResponse.class);
            
            log.info("Successfully obtained presigned URL for file ID: {}", uploadResponse.getFileId());
            return uploadResponse;
        }
    }
    
    /**
     * Upload file using presigned URL.
     * 
     * @param fileObject the file object to upload
     * @param presignedUrl the presigned URL from requestPresignedUrl()
     * @throws IOException if the upload fails
     */
    public void uploadFile(FileObject fileObject, String presignedUrl) throws IOException {
        long uploadStartTime = System.currentTimeMillis();
        log.info("Uploading file to Reducto: {} ({} bytes) to URL: {}", fileObject.getFileName(), fileObject.getSizeBytes(), presignedUrl);
        
        // 🚫 Don't include MediaType here — omit Content-Type
        RequestBody fileBody = RequestBody.create(fileObject.getContent());
        
        Request request = new Request.Builder()
            .url(presignedUrl)
            .put(fileBody)
            .build();
        
        log.info("Upload request details: Content-Length={}, URL={}", 
            fileObject.getSizeBytes(), presignedUrl);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                
                if (response.code() == 403) {
                    log.error("Reducto file upload failed with 403 Forbidden - Presigned URL may have expired or signature is invalid: {}", errorBody);
                    throw new ReductoUploadException("Presigned URL expired or invalid signature (403): " + errorBody);
                } else {
                    log.error("Reducto file upload failed with status {}: {}", response.code(), errorBody);
                    throw new ReductoUploadException("Failed to upload file: " + response.code() + " - " + errorBody);
                }
            }
            
            long uploadDuration = System.currentTimeMillis() - uploadStartTime;
            log.info("Successfully uploaded file: {} (took {}ms)", fileObject.getFileName(), uploadDuration);
        }
    }
    
    /**
     * Create an async extract task for a file.
     *
     * @param fileId the file ID from upload (this is the assignedId from FileObject)
     * @param schema the extraction schema as JSON string
     * @param systemPrompt the system prompt for extraction
     * @return ReductoAsyncExtractResponse with job details
     * @throws IOException if the request fails
     */
    public ReductoAsyncExtractResponse createAsyncExtractTask(String fileId, String schema, String systemPrompt) throws IOException {
        log.info("Creating async extract task for file ID: {}", fileId);
        
        // Build the async extract request with enhanced settings
        ReductoExtractRequest extractRequest = ReductoExtractRequest.builder()
            .async(ReductoAsyncConfig.builder()
                .priority(false) // Explicit boolean instead of null
                .metadata(Map.of("assignedId", fileId)) // Include assignedId in metadata
                .webhook(ReductoWebhookConfig.builder()
                    .mode("svix")
                    .channels(new String[]{"default"})
                    .build())
                .build())
            .input(fileId) // fileId is already the correct format
            .parsing(ReductoParsing.builder()
                .enhance(ReductoEnhance.builder()
                    .agentic(new ReductoAgentic[]{}) // Empty array
                    .summarizeFigures(true)
                    .build())
                .retrieval(ReductoRetrieval.builder()
                    .chunking(ReductoChunking.builder()
                        .chunkMode("disabled")
                        .chunkSize(null)
                        .build())
                    .filterBlocks(Collections.emptyList())
                    .embeddingOptimized(false)
                    .build())
                .formatting(ReductoFormatting.builder()
                    .addPageMarkers(false)
                    .tableOutputFormat("dynamic")
                    .mergeTables(false)
                    .include(Collections.emptyList())
                    .build())
                .spreadsheet(ReductoSpreadsheet.builder()
                    .splitLargeTables(ReductoSplitLargeTables.builder()
                        .enabled(true)
                        .size(50)
                        .build())
                    .include(Collections.emptyList())
                    .clustering("accurate")
                    .exclude(Collections.emptyList())
                    .build())
                .settings(ReductoParseSettings.builder()
                    .timeout(900)
                    .ocrSystem("standard")
                    .forceUrlResult(false)
                    .returnOcrData(false)
                    .returnImages(Collections.emptyList())
                    .embedPdfMetadata(false)
                    .persistResults(true)
                    .build())
                .build())
             .instructions(Instructions.builder()
                 .schema(schema)
                 .systemPrompt(systemPrompt)
                 .build())
            .settings(Settings.builder()
                .citations(Citations.builder().enabled(true).build())
                .arrayExtract(true)
                .build())
            .build();
        
        String requestBody = objectMapper.writeValueAsString(extractRequest);
        log.debug("Request body: {}", requestBody);

        Request request = new Request.Builder()
            .url(BASE_URL + EXTRACT_ASYNC_ENDPOINT)
            .addHeader(HEADER_AUTHORIZATION, "Bearer " + apiKey)
            .addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .post(RequestBody.create(requestBody, MediaType.get(CONTENT_TYPE_JSON)))
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                
                if (response.code() == 401 || response.code() == 422) {
                    throw new StartTaskException("Start task failed (" + response.code() + "): " + errorBody);
                } else {
                    throw new ReductoTaskException("Failed to create async extract task: " + response.code() + " - " + errorBody);
                }
            }
            
            String responseBody = response.body().string();
            ReductoAsyncExtractResponse extractResponse = objectMapper.readValue(responseBody, ReductoAsyncExtractResponse.class);
            
            log.info("Successfully created async extract task: {}", extractResponse.getJobId());
            return extractResponse;
        }
    }
    
    /**
     * Get extract task by job ID.
     * Note: Reducto async tasks don't have a status endpoint, so this method is not implemented.
     * 
     * @param jobId the job ID from createAsyncExtractTask
     * @return null (not supported for async tasks)
     * @throws UnsupportedOperationException always thrown since async tasks don't support status checking
     */
    public ReductoJobStatusResponse getJobStatus(String jobId) throws IOException {
        log.info("Getting job status from Reducto: jobId={}", jobId);

        Request request = new Request.Builder()
            .url(BASE_URL + "/job/" + jobId)
            .addHeader(HEADER_AUTHORIZATION, "Bearer " + apiKey)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new ReductoTaskException("Failed to get job status: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            ReductoJobStatusResponse jobStatus = objectMapper.readValue(responseBody, ReductoJobStatusResponse.class);
            jobStatus.setRawResponse(responseBody);
            log.info("Successfully retrieved job status: jobId={}, status={}", jobId, jobStatus.getStatus());
            return jobStatus;
        }
    }
}
