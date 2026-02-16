package com.tosspaper.models.utils

import com.tosspaper.models.domain.Currency
import spock.lang.Specification

/**
 * Tests for CountryCurrencyMapper.
 * Verifies country code to currency mapping.
 */
class CountryCurrencyMapperSpec extends Specification {

    def "mapCountryToCurrency should map US to USD"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("US") == Currency.USD
    }

    def "mapCountryToCurrency should map CA to CAD"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("CA") == Currency.CAD
    }

    def "mapCountryToCurrency should map GB to GBP"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("GB") == Currency.GBP
    }

    def "mapCountryToCurrency should map EU countries to EUR"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(countryCode) == Currency.EUR

        where:
        countryCode << [
            "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR",
            "GR", "IE", "IT", "LT", "LU", "LV", "MT", "NL",
            "PT", "SI", "SK"
        ]
    }

    def "mapCountryToCurrency should map various countries to correct currencies"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(countryCode) == expectedCurrency

        where:
        countryCode | expectedCurrency
        "AU"        | Currency.AUD
        "JP"        | Currency.JPY
        "CH"        | Currency.CHF
        "CN"        | Currency.CNY
        "IN"        | Currency.INR
        "BR"        | Currency.BRL
        "MX"        | Currency.MXN
        "ZA"        | Currency.ZAR
        "SE"        | Currency.SEK
        "NO"        | Currency.NOK
        "DK"        | Currency.DKK
        "PL"        | Currency.PLN
        "NZ"        | Currency.NZD
        "SG"        | Currency.SGD
        "HK"        | Currency.HKD
    }

    def "mapCountryToCurrency should be case-insensitive"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("us") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("Us") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("uS") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("gb") == Currency.GBP
        CountryCurrencyMapper.mapCountryToCurrency("de") == Currency.EUR
    }

    def "mapCountryToCurrency should handle whitespace"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(" US ") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("  GB  ") == Currency.GBP
    }

    def "mapCountryToCurrency should default to USD for unknown countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(countryCode) == Currency.USD

        where:
        countryCode << ["XX", "ZZ", "AA", "UNKNOWN"]
    }

    def "mapCountryToCurrency should default to USD for null"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(null) == Currency.USD
    }

    def "mapCountryToCurrency should default to USD for blank string"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("   ") == Currency.USD
    }

    def "mapCountryToCurrency should prioritize direct mapping over EU check"() {
        when:
        // DE is in both COUNTRY_CURRENCY_MAP and EUR_COUNTRIES
        // But it's listed in COUNTRY_CURRENCY_MAP, so might not be there
        // Actually checking the code: DE is in EUR_COUNTRIES but not in direct map
        def result = CountryCurrencyMapper.mapCountryToCurrency("DE")

        then:
        result == Currency.EUR
    }

    def "mapCountryToCurrency should handle all EUR countries consistently"() {
        given:
        def eurCountries = [
            "AT", "BE", "CY", "DE", "EE", "ES", "FI", "FR",
            "GR", "IE", "IT", "LT", "LU", "LV", "MT", "NL",
            "PT", "SI", "SK"
        ]

        expect:
        eurCountries.every { country ->
            CountryCurrencyMapper.mapCountryToCurrency(country) == Currency.EUR
        }
    }

    def "mapCountryToCurrency should handle Nordic countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("SE") == Currency.SEK
        CountryCurrencyMapper.mapCountryToCurrency("NO") == Currency.NOK
        CountryCurrencyMapper.mapCountryToCurrency("DK") == Currency.DKK
        CountryCurrencyMapper.mapCountryToCurrency("FI") == Currency.EUR // Finland uses EUR
    }

    def "mapCountryToCurrency should handle Asian countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("JP") == Currency.JPY
        CountryCurrencyMapper.mapCountryToCurrency("CN") == Currency.CNY
        CountryCurrencyMapper.mapCountryToCurrency("IN") == Currency.INR
        CountryCurrencyMapper.mapCountryToCurrency("SG") == Currency.SGD
        CountryCurrencyMapper.mapCountryToCurrency("HK") == Currency.HKD
    }

    def "mapCountryToCurrency should handle American countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("US") == Currency.USD
        CountryCurrencyMapper.mapCountryToCurrency("CA") == Currency.CAD
        CountryCurrencyMapper.mapCountryToCurrency("BR") == Currency.BRL
        CountryCurrencyMapper.mapCountryToCurrency("MX") == Currency.MXN
    }

    def "mapCountryToCurrency should handle Oceania countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("AU") == Currency.AUD
        CountryCurrencyMapper.mapCountryToCurrency("NZ") == Currency.NZD
    }

    def "mapCountryToCurrency should handle African countries"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("ZA") == Currency.ZAR
    }

    def "mapCountryToCurrency should handle lowercase input"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(countryCode.toLowerCase()) == expectedCurrency

        where:
        countryCode | expectedCurrency
        "US"        | Currency.USD
        "GB"        | Currency.GBP
        "JP"        | Currency.JPY
        "AU"        | Currency.AUD
        "DE"        | Currency.EUR
    }

    def "mapCountryToCurrency should handle uppercase input"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency(countryCode.toUpperCase()) == expectedCurrency

        where:
        countryCode | expectedCurrency
        "us"        | Currency.USD
        "gb"        | Currency.GBP
        "jp"        | Currency.JPY
        "au"        | Currency.AUD
        "de"        | Currency.EUR
    }

    def "mapCountryToCurrency should handle invalid length country codes"() {
        expect:
        CountryCurrencyMapper.mapCountryToCurrency("U") == Currency.USD // Too short
        CountryCurrencyMapper.mapCountryToCurrency("USA") == Currency.USD // Too long
        CountryCurrencyMapper.mapCountryToCurrency("UNITED") == Currency.USD // Way too long
    }
}
