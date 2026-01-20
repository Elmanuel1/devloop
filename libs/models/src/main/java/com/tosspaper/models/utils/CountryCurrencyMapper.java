package com.tosspaper.models.utils;

import com.tosspaper.models.domain.Currency;

import java.util.Map;
import java.util.Set;

/**
 * Utility class to map ISO 3166-1 alpha-2 country codes to Currency enum.
 * Used to determine default currency based on country of incorporation.
 */
public class CountryCurrencyMapper {

    // EU countries that use EUR
    private static final Set<String> EUR_COUNTRIES = Set.of(
            "AT", // Austria
            "BE", // Belgium
            "CY", // Cyprus
            "DE", // Germany
            "EE", // Estonia
            "ES", // Spain
            "FI", // Finland
            "FR", // France
            "GR", // Greece
            "IE", // Ireland
            "IT", // Italy
            "LT", // Lithuania
            "LU", // Luxembourg
            "LV", // Latvia
            "MT", // Malta
            "NL", // Netherlands
            "PT", // Portugal
            "SI", // Slovenia
            "SK"  // Slovakia
    );

    // Direct country to currency mapping
    private static final Map<String, Currency> COUNTRY_CURRENCY_MAP = Map.ofEntries(
            Map.entry("US", Currency.USD), // United States
            Map.entry("CA", Currency.CAD), // Canada
            Map.entry("GB", Currency.GBP), // United Kingdom
            Map.entry("AU", Currency.AUD), // Australia
            Map.entry("JP", Currency.JPY), // Japan
            Map.entry("CH", Currency.CHF), // Switzerland
            Map.entry("CN", Currency.CNY), // China
            Map.entry("IN", Currency.INR), // India
            Map.entry("BR", Currency.BRL), // Brazil
            Map.entry("MX", Currency.MXN), // Mexico
            Map.entry("ZA", Currency.ZAR), // South Africa
            Map.entry("SE", Currency.SEK), // Sweden
            Map.entry("NO", Currency.NOK), // Norway
            Map.entry("DK", Currency.DKK), // Denmark
            Map.entry("PL", Currency.PLN), // Poland
            Map.entry("NZ", Currency.NZD), // New Zealand
            Map.entry("SG", Currency.SGD), // Singapore
            Map.entry("HK", Currency.HKD)  // Hong Kong
    );

    /**
     * Map ISO 3166-1 alpha-2 country code to Currency enum.
     * 
     * @param countryCode 2-letter ISO country code (e.g., "US", "CA", "FR")
     * @return Currency enum, defaults to USD if country not found or cannot be determined
     */
    public static Currency mapCountryToCurrency(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Currency.USD;
        }

        String normalized = countryCode.toUpperCase().trim();

        // Check direct mapping first
        Currency currency = COUNTRY_CURRENCY_MAP.get(normalized);
        if (currency != null) {
            return currency;
        }

        // Check if it's an EU country that uses EUR
        if (EUR_COUNTRIES.contains(normalized)) {
            return Currency.EUR;
        }

        // Country not found - default to USD
        return Currency.USD;
    }
}

