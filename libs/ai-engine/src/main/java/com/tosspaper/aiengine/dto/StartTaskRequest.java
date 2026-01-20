package com.tosspaper.aiengine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartTaskRequest {
    private String preparationId;
    private String schema;
    private String prompt;
    private String assignedId;
}
