package com.tosspaper.precon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SQS message handler for processing S3 ObjectCreated event notifications
 * for tender document uploads. Receives S3 event JSON from SQS and delegates
 * to DocumentUploadProcessor for file validation.
 *
 * The SQS message body wraps an S3 event notification as a JSON string inside
 * a "Message" field (when coming through SNS) or directly as an S3 event.
 */
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
        try {
            // The SQS consumer manager deserializes the body as Map<String, String>.
            // For S3 event notifications sent directly to SQS, the entire message body
            // is the S3 event JSON. We need to re-serialize and parse it as S3EventMessage.
            String messageJson = objectMapper.writeValueAsString(message);
            handleMessage(messageJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message map back to JSON", e);
        }
    }

    /**
     * Processes an S3 event notification message.
     * Package-private for direct testing with raw JSON.
     *
     * @param messageBody the raw JSON string of the S3 event or SQS wrapper
     */
    void handleMessage(String messageBody) {
        try {
            S3EventMessage event = parseS3Event(messageBody);
            if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
                log.warn("No records found in S3 event message");
                return;
            }

            for (S3EventMessage.Record record : event.getRecords()) {
                processRecord(record);
            }

        } catch (Exception e) {
            log.error("Failed to process S3 event message: {}", messageBody, e);
            throw new RuntimeException("Failed to process S3 event message", e);
        }
    }

    private void processRecord(S3EventMessage.Record record) {
        if (record.getS3() == null) {
            log.warn("S3 event record missing s3 field");
            return;
        }

        String bucket = record.getS3().getBucket() != null
                ? record.getS3().getBucket().getName() : null;
        String key = record.getS3().getObject() != null
                ? record.getS3().getObject().getKey() : null;
        long size = record.getS3().getObject() != null
                ? record.getS3().getObject().getSize() : 0;

        if (bucket == null || key == null) {
            log.warn("S3 event record missing bucket or key");
            return;
        }

        // URL-decode the key (S3 event notifications URL-encode the object key)
        key = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8);

        log.info("Processing S3 event - bucket: {}, key: {}, size: {}", bucket, key, size);
        processor.processUpload(bucket, key, size);
    }

    private S3EventMessage parseS3Event(String json) {
        try {
            // First try parsing directly as S3 event
            return objectMapper.readValue(json, S3EventMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse S3 event JSON: {}", json, e);
            return null;
        }
    }
}
