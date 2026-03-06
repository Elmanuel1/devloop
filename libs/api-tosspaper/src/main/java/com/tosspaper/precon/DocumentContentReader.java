package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Downloads document bytes from S3 for the extraction pipeline. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentContentReader {

    private final S3Client s3Client;
    private final TenderFileProperties fileProperties;

    @SneakyThrows
    public byte[] read(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(fileProperties.getUploadBucket())
                .key(s3Key)
                .build();
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
            return stream.readAllBytes();
        }
    }
}
