package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Address
 */

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Address implements Serializable {

  private String address;

  private String city;

  private String country;

  @JsonProperty("state_or_province")
  private String stateOrProvince;

  @JsonProperty("postal_code")
  private String postalCode;
}

