package com.tosspaper.models.service.impl;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.storage.DownloadResult;
import com.tosspaper.models.storage.S3UploadResult;
import com.tosspaper.models.storage.UploadResult;
import com.tosspaper.models.properties.AwsProperties;
import com.tosspaper.models.properties.FileProperties;
import com.tosspaper.models.service.StorageService;
import com.tosspaper.models.utils.FileNameSanitizer;
import com.tosspaper.models.utils.S3KeyUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AWS S3 implementation of StorageService.
 * Handles file uploads to S3 with proper error handling and metadata extraction.
 */
@RequiredArgsConstructor
@Slf4j
@Service("s3StorageService")
public class S3StorageServiceImpl implements StorageService, SmartLifecycle {

    private final S3Client s3Client;
    private final AwsProperties awsProperties;
    private final FileProperties fileProperties;
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = false;

    @Override
    public List<UploadResult> uploadFiles(List<FileObject> fileObjects) {
        log.info("Starting upload of {} files to S3", fileObjects.size());
        
        List<UploadResult> results = new ArrayList<>();
        
        for (FileObject fileObject : fileObjects) {
            UploadResult result = uploadFile(fileObject);
            results.add(result);
        }
        
        log.info("Completed upload batch: {} successful, {} failed", 
            results.stream().mapToLong(r -> r.isSuccessful() ? 1 : 0).sum(),
            results.stream().mapToLong(r -> r.isFailed() ? 1 : 0).sum());
        
        return results;
    }

    @Override
    public CompletableFuture<List<UploadResult>> uploadFilesAsync(List<FileObject> fileObjects) {
        log.info("Starting async upload of {} files to S3", fileObjects.size());

        // Create upload futures directly from the stream
        List<CompletableFuture<UploadResult>> uploadFutures = fileObjects.stream()
                .map(fileObject -> CompletableFuture.supplyAsync(
                        () -> uploadFile(fileObject),
                        virtualThreadExecutor))
                .toList();

        // Combine all futures and collect results
        return CompletableFuture.allOf(uploadFutures.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    List<UploadResult> results = uploadFutures.stream()
                            .map(CompletableFuture::join)
                            .toList();

                    long successful = results.stream().filter(UploadResult::isSuccessful).count();
                    long failed = results.size() - successful;

                    log.info("Completed async upload batch: {} successful, {} failed", successful, failed);
                    return results;
                })
                .exceptionally(ex -> {
                    log.error("Upload batch failed with exception", ex);
                    throw new RuntimeException(ex);
                });
    }

    /**
     * Uploads a single file to S3 with the given key.
     * This method is designed to be called from virtual threads.
     *
     * @param fileObject the file to upload
     * @return the upload result
     */
    @Override
    public UploadResult uploadFile(FileObject fileObject) {
        try {
            FileObject sanitizedFileObject = new FileNameSanitizer(fileProperties.getReplacementMap()).sanitizeFileObject(fileObject);
        
            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(awsProperties.getBucket().getName())
                .key(sanitizedFileObject.getKey())
                .contentType(sanitizedFileObject.getContentType())
                .contentLength((long) sanitizedFileObject.getContent().length)
                .metadata(sanitizedFileObject.getMetadata())
                .build();

            // Upload to S3
            PutObjectResponse response  = s3Client.putObject(putRequest, RequestBody.fromBytes(sanitizedFileObject.getContent()));

            // Create S3 metadata including region
            Map<String, String> s3Metadata = new HashMap<>();
            s3Metadata.put("bucket", awsProperties.getBucket().getName());
            s3Metadata.put("upload-timestamp", java.time.Instant.now().toString());

        
            S3UploadResult result = S3UploadResult.success(
                sanitizedFileObject.getKey(),
                sanitizedFileObject.getChecksum(),
                sanitizedFileObject.getContent().length,
                sanitizedFileObject.getContentType(),
                s3Metadata,
                awsProperties.getBucket().getRegion(),
                awsProperties.getBucket().getName(),
                null, // ETag - would need to get from S3 response
                null, // VersionId - would need to get from S3 response
                awsProperties.getBucket().getEndpoint()
            );

            log.info("Successfully uploaded file: {} -> {}",
                sanitizedFileObject.getFileName(), result.key());
            
            return result;

        } catch (S3Exception e) {
            log.error("S3 error uploading file: {} - Code: {}, Message: {}", 
                fileObject.getFileName(), e.awsErrorDetails().errorCode(), e.getMessage());
            return S3UploadResult.failure(e);
            
        } catch (Exception e) {
            log.error("Unexpected error uploading file: {}", fileObject.getFileName(), e);
            return S3UploadResult.failure(e);
        }
    }
    
    @Override
    public DownloadResult download(String key) {
        try {
            log.info("Downloading file from S3 with key: {}", key);
            
            GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(awsProperties.getBucket().getName())
                .key(key)
                .build();
            
            byte[] content;
            GetObjectResponse response;
            try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(getRequest)) {
                response = responseStream.response();
                content = responseStream.readAllBytes();
            }
            
            // Build FileObject
            FileObject fileObject = FileObject.builder()
                .key(key)
                .fileName(S3KeyUtils.extractFileNameFromKey(key))
                .contentType(response.contentType())
                .content(content)
                .sizeBytes(content.length)
                .metadata(response.metadata())
                .build();
            
            log.info("Successfully downloaded file: {} ({} bytes)", key, fileObject.getSizeBytes());
            
            // Create S3-specific metadata
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("bucket", awsProperties.getBucket().getName());
            metadata.put("download-timestamp", java.time.Instant.now().toString());
            metadata.put("content-length", fileObject.getSizeBytes());
            metadata.put("last-modified", "N/A");
            
            return DownloadResult.success(key, fileObject, metadata);
            
        } catch (S3Exception e) {
            log.error("S3 error downloading file: {} - Code: {}, Message: {}", 
                key, e.awsErrorDetails().errorCode(), e.getMessage());
            return DownloadResult.failure(key, e);
        } catch (Exception e) {
            log.error("Unexpected error downloading file: {}", key, e);
            return DownloadResult.failure(key, e);
        }
    }
    
    
    /**
     * Converts S3UploadResult to UploadResult for interface compatibility.
     * 
     * @param s3Result the S3-specific upload result
     * @return generic UploadResult
     */
    private UploadResult convertToUploadResult(S3UploadResult s3Result) {
        return new UploadResult(
            s3Result.key(),
            s3Result.checksum(),
            s3Result.actualSizeBytes(),
            s3Result.contentType(),
            s3Result.uploadSuccessful(),
            s3Result.error(),
            s3Result.validationResult(),
            s3Result.metadata()
        );
    }

    // SmartLifecycle implementation

    @Override
    public void start() {
        log.info("Starting S3StorageServiceImpl");
        running = true;
    }

    @Override
    public void stop() {
        log.info("Stopping S3StorageServiceImpl - shutting down executor");
        running = false;
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate in 30s, forcing shutdown");
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for executor shutdown", e);
            virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

}
