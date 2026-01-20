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
public class ReductoFormatting {
    private Boolean addPageMarkers;
    private String tableOutputFormat; // "dynamic"
    private Boolean mergeTables;
    private List<String> include;
}
