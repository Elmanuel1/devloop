package com.tosspaper.precon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentUploadHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "tender-upload-notifications";

    private final DocumentUploadProcessor processor;
    private final ObjectMapper objectMapper;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        S3EventMessage event = objectMapper.convertValue(message, S3EventMessage.class);
        event.getRecords().forEach(this::processRecord);
    }

    private void processRecord(S3EventMessage.Record record) {
        String bucket = record.getBucketName();
        String key = record.getObjectKey();
        long size = record.getObjectSize();

        if (bucket == null || key == null) {
            log.warn("S3 event record missing bucket or key");
            return;
        }

        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        log.info("Processing S3 event - bucket: {}, key: {}, size: {}", bucket, key, size);
        processor.processUpload(bucket, key, size);
    }
}
