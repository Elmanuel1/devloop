package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents field-by-field comparison of contact information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContactComparison {
    
    private ComparisonField name;
    private AddressComparison address;
    private ComparisonField phone;
    private ComparisonField email;
}

