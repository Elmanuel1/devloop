package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Company {
    private Long id;
    private String name;
    private String email;
    private String description;
    private String logoUrl;
    private String termsUrl;
    private String privacyUrl;
  private String assignedEmail;
  private String metadata;
  private Currency currency;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}

