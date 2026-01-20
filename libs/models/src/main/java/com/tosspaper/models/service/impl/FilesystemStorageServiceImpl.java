package com.tosspaper.models.service.impl;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.storage.DownloadResult;
import com.tosspaper.models.storage.UploadResult;
import com.tosspaper.models.properties.FileProperties;
import com.tosspaper.models.service.StorageService;
import com.tosspaper.models.utils.FileNameSanitizer;
import com.tosspaper.models.utils.S3KeyUtils;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Filesystem implementation of StorageService.
 * Saves files locally and returns pending status for later S3 upload.
 */
@RequiredArgsConstructor
@Slf4j
@Service("filesystemStorageService")
public class FilesystemStorageServiceImpl implements StorageService {

    private final FileProperties fileProperties;

    @Override
    public List<UploadResult> uploadFiles(List<FileObject> fileObjects) {
        log.info("Starting filesystem upload of {} files", fileObjects.size());

        List<UploadResult> results = fileObjects.stream()
            .map(this::uploadFile)
            .toList();

        log.info("Completed filesystem upload batch: {} successful, {} failed",
            results.stream().mapToLong(r -> r.isSuccessful() ? 1 : 0).sum(),
            results.stream().mapToLong(r -> r.isFailed() ? 1 : 0).sum());

        return results;
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<UploadResult>> uploadFilesAsync( List<FileObject> fileObjects) {
        throw new UnsupportedOperationException("Async filesystem upload not supported - use synchronous uploadFiles() instead");
    }

    /**
     * Uploads a single file to local filesystem.
     * 
     * @param fileObject the file to upload
     * @return the upload result with pending status
     */
    @Override
    public UploadResult uploadFile(FileObject fileObject) {
        FileObject sanitizedFileObject = new FileNameSanitizer(fileProperties.getReplacementMap())
        .sanitizeFileObject(fileObject);

        Path filePath = createLocalFilePath(sanitizedFileObject.getKey());
        // Create parent directories if they don't exist
        Try<Void> dirCreation = Try.run(() -> Files.createDirectories(filePath.getParent()))
            .recover(FileAlreadyExistsException.class, e -> null); // Directory already exists is fine

        if (dirCreation.isFailure()) {
            Throwable cause = dirCreation.getCause();
            log.error("Error creating directories for file: {}", sanitizedFileObject.getFileName(), cause);
            return UploadResult.failure(cause);
        }

    // Write file to local filesystem
        return Try.of(() -> Files.write(filePath, sanitizedFileObject.getContent(), StandardOpenOption.CREATE))
        .map(p -> UploadResult.success(
                filePath.toString(),
                calculateChecksum(sanitizedFileObject.getContent()),
                sanitizedFileObject.getContent().length,
                sanitizedFileObject.getContentType()
        ))
        .peek(r -> log.info("Successfully written file: {}. Attachment id {}", r.key(), fileObject.getAssignedId()))
        .recover(Throwable.class, throwable -> {
            log.error("Error writing file: {}", sanitizedFileObject.getFileName(), throwable);
            return UploadResult.failure(throwable);
        }).get();
    }


    /**
     * Create local file path from key.
     * 
     * @param key the storage key
     * @return Path object for the file
     */
    private Path createLocalFilePath(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("File key cannot be null or blank");
        }

        // Prevent path traversal attacks
        if (key.contains("..") || key.startsWith("/") || key.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid file key: path traversal not allowed");
        }

        String basePath = fileProperties.getFilesystemPath();
        Path resolvedPath = Paths.get(basePath, key).normalize();
        Path basePathResolved = Paths.get(basePath).toAbsolutePath().normalize();

        // Ensure resolved path is within base path
        if (!resolvedPath.toAbsolutePath().normalize().startsWith(basePathResolved)) {
            throw new IllegalArgumentException("Invalid file key: path escapes storage directory");
        }

        return resolvedPath;
    }

    @Override
    public DownloadResult download(String key) {
        try {
            log.info("Downloading file from filesystem with key: {}", key);

            Path filePath = createLocalFilePath(key);

            if (!Files.exists(filePath)) {
                return DownloadResult.failure(key, new FileNotFoundException("File not found: " + filePath));
            }
            
            byte[] content = Files.readAllBytes(filePath);
            String fileName = S3KeyUtils.extractFileNameFromKey(key);
            String contentType = Files.probeContentType(filePath);
            
            FileObject fileObject = FileObject.builder()
                .key(key)
                .fileName(fileName)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .content(content)
                .sizeBytes(content.length)
                .build();
            
            log.info("Successfully downloaded file: {} ({} bytes)", key, content.length);
            
            // Create filesystem-specific metadata
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("file-path", filePath.toString());
            metadata.put("download-timestamp", java.time.Instant.now().toString());
            metadata.put("file-size", content.length);
            metadata.put("last-modified", Files.getLastModifiedTime(filePath).toString());
            
            return DownloadResult.success(key, fileObject, metadata);
            
        } catch (IOException e) {
            log.error("Error downloading file from filesystem: {}", key, e);
            return DownloadResult.failure(key, e);
        } catch (Exception e) {
            log.error("Unexpected error downloading file: {}", key, e);
            return DownloadResult.failure(key, e);
        }
    }
    
    /**
     * Calculate SHA-256 checksum of file content.
     * 
     * @param content the file content
     * @return SHA-256 checksum as hex string
     */
    private String calculateChecksum(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
