package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * POJO for deserializing S3 event notification JSON received from SQS.
 * S3 sends event notifications in this format when objects are created/deleted.
 *
 * Example structure:
 * <pre>
 * {
 *   "Records": [{
 *     "s3": {
 *       "bucket": { "name": "my-bucket" },
 *       "object": { "key": "tender-uploads/1/tid/did/file.pdf", "size": 12345 }
 *     }
 *   }]
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3EventMessage {

    @JsonProperty("Records")
    private List<Record> records;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Record {
        private S3 s3;

        public String getBucketName() {
            return s3 != null && s3.bucket != null ? s3.bucket.name : null;
        }

        public String getObjectKey() {
            return s3 != null && s3.object != null ? s3.object.key : null;
        }

        public long getObjectSize() {
            return s3 != null && s3.object != null ? s3.object.size : 0;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class S3 {
        private Bucket bucket;
        private S3Object object;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bucket {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class S3Object {
        private String key;
        private long size;
    }
}
