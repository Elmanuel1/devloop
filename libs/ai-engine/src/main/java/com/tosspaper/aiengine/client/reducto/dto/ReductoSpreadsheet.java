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
public class ReductoSpreadsheet {
    private ReductoSplitLargeTables splitLargeTables;
    private List<String> include;
    private String clustering; // "accurate"
    private List<String> exclude;
}
