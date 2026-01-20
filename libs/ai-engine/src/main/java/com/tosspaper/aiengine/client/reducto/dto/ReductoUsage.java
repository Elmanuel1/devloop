package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Usage information from Reducto extract response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoUsage {
    private Integer numPages;
    private Integer numFields;
    private Double credits;
}
