package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents field-by-field comparison of addresses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddressComparison {
    private ComparisonField street;
    private ComparisonField city;
    private ComparisonField stateOrProvince;
    private ComparisonField postalCode;
    private ComparisonField country;
}

