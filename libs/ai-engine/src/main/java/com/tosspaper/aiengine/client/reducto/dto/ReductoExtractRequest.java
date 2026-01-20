package com.tosspaper.aiengine.client.reducto.dto;

import com.tosspaper.aiengine.client.common.dto.Instructions;
import com.tosspaper.aiengine.client.common.dto.Settings;
import lombok.Builder;
import lombok.Data;

/**
 * Request for Reducto extract endpoint.
 */
@Data
@Builder
public class ReductoExtractRequest {
    private ReductoAsyncConfig async; // Optional - only for async requests
    private String input;  // file_id
    private ReductoParsing parsing;
    private Instructions instructions;
    private Settings settings;
}
