package com.tosspaper.integrations.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthUrlResponse {
    private String authUrl;
    private String state;
}