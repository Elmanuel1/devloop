package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;

/**
 * Reads raw document bytes from S3 for use by the extraction pipeline.
 *
 * <p>Extracts the S3 I/O concern out of {@link ExtractionWorker} so that the
 * worker stays within the five-dependency limit and each class has a single
 * responsibility.
 *
 * <h3>Error handling</h3>
 * <p>Any {@link IOException} or {@link SdkException} is caught, logged with full
 * context, and converted to a {@code null} return value. Callers treat {@code null}
 * as a download failure and mark the document accordingly — no checked exception
 * propagates out of this class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentContentReader {

    private final S3Client s3Client;
    private final TenderFileProperties fileProperties;

    /**
     * Downloads all bytes for the given S3 key from the configured upload bucket.
     *
     * @param extractionId the extraction ID (used for log context only)
     * @param documentId   the document ID (used for log context only)
     * @param s3Key        the S3 object key to download
     * @return the document bytes, or {@code null} on any I/O or SDK failure
     */
    public byte[] read(String extractionId, String documentId, String s3Key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(fileProperties.getUploadBucket())
                    .key(s3Key)
                    .build();
            try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
                return stream.readAllBytes();
            }
        } catch (IOException | SdkException e) {
            log.error("[DocumentContentReader] Extraction '{}' document '{}' — S3 content download failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return null;
        }
    }
}
