package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoRetrieval {
    private ReductoChunking chunking;
    private List<String> filterBlocks;
    private Boolean embeddingOptimized;
}
