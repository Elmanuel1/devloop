package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * ISO 4217 currency codes.
 * Standardized currency representation for multi-provider support.
 */
public enum Currency {
    USD("USD", "United States Dollar"),
    CAD("CAD", "Canadian Dollar"),
    EUR("EUR", "Euro"),
    GBP("GBP", "British Pound"),
    AUD("AUD", "Australian Dollar"),
    JPY("JPY", "Japanese Yen"),
    CHF("CHF", "Swiss Franc"),
    CNY("CNY", "Chinese Yuan"),
    INR("INR", "Indian Rupee"),
    BRL("BRL", "Brazilian Real"),
    MXN("MXN", "Mexican Peso"),
    ZAR("ZAR", "South African Rand"),
    SEK("SEK", "Swedish Krona"),
    NOK("NOK", "Norwegian Krone"),
    DKK("DKK", "Danish Krone"),
    PLN("PLN", "Polish Zloty"),
    NZD("NZD", "New Zealand Dollar"),
    SGD("SGD", "Singapore Dollar"),
    HKD("HKD", "Hong Kong Dollar"),
    AED("AED", "UAE Dirham"),
    SAR("SAR", "Saudi Riyal"),
    KWD("KWD", "Kuwaiti Dinar"),
    QAR("QAR", "Qatari Riyal"),
    BHD("BHD", "Bahraini Dinar"),
    OMR("OMR", "Omani Rial"),
    ILS("ILS", "Israeli Shekel"),
    TRY("TRY", "Turkish Lira"),
    EGP("EGP", "Egyptian Pound"),
    THB("THB", "Thai Baht"),
    MYR("MYR", "Malaysian Ringgit"),
    IDR("IDR", "Indonesian Rupiah"),
    PHP("PHP", "Philippine Peso"),
    VND("VND", "Vietnamese Dong"),
    KRW("KRW", "South Korean Won"),
    RUB("RUB", "Russian Ruble"),
    CZK("CZK", "Czech Koruna"),
    HUF("HUF", "Hungarian Forint"),
    RON("RON", "Romanian Leu"),
    BGN("BGN", "Bulgarian Lev"),
    HRK("HRK", "Croatian Kuna");

    private final String code;
    @Getter
    private final String displayName;

    Currency(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }

    /**
     * Standardize currency value from QuickBooks (handles case variations, etc.)
     * 
     * @param qboValue Currency code from QuickBooks (e.g., from CurrencyRef.getValue())
     * @return Currency enum or null if not recognized
     */
    @JsonCreator
    public static Currency fromQboValue(String qboValue) {
        if (qboValue == null || qboValue.isBlank()) {
            return null;
        }
        String normalized = qboValue.toUpperCase().trim();
        for (Currency currency : values()) {
            if (currency.code.equals(normalized)) {
                return currency;
            }
        }
        // Return null if not recognized (caller can handle fallback)
        return null;
    }

    /**
     * Parse currency code string to enum.
     * 
     * @param code Currency code string (e.g., "USD", "CAD")
     * @return Currency enum or null if not recognized
     */
    public static Currency fromCode(String code) {
        return fromQboValue(code);
    }
}

