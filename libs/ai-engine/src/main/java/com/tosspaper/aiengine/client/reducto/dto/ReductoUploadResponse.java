package com.tosspaper.aiengine.client.reducto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from Reducto upload endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoUploadResponse {
    @JsonProperty("file_id")
    private String fileId; 
    
    @JsonProperty("presigned_url")
    private String presignedUrl;
}
