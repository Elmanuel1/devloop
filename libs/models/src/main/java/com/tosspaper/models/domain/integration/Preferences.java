package com.tosspaper.models.domain.integration;

import com.tosspaper.models.domain.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain model for integration Preferences (e.g., default currency, multicurrency settings).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Preferences {
    private Currency defaultCurrency;
    private Boolean multicurrencyEnabled;
}

