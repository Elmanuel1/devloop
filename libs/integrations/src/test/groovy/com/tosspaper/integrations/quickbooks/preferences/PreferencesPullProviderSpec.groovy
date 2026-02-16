package com.tosspaper.integrations.quickbooks.preferences

import com.intuit.ipp.data.CurrencyPrefs
import com.intuit.ipp.data.Preferences
import com.intuit.ipp.data.ReferenceType
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class PreferencesPullProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    QuickBooksProperties properties = Mock()

    @Subject
    PreferencesPullProvider provider = new PreferencesPullProvider(apiClient, properties)

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return correct provider ID"() {
        expect:
            provider.providerId == IntegrationProvider.QUICKBOOKS
    }

    def "should return correct entity type"() {
        expect:
            provider.entityType == IntegrationEntityType.PREFERENCES
    }

    def "should return enabled status from properties"() {
        given: "properties enabled"
            properties.isEnabled() >> true

        expect:
            provider.isEnabled()
    }

    def "pullBatch should return preferences with currency and multicurrency"() {
        given: "QBO returns preferences with currency prefs"
            def homeRef = new ReferenceType()
            homeRef.value = "USD"

            def currencyPrefs = new CurrencyPrefs()
            currencyPrefs.homeCurrency = homeRef
            currencyPrefs.multiCurrencyEnabled = true

            def qboPrefs = new Preferences()
            qboPrefs.currencyPrefs = currencyPrefs

        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then: "API is queried"
            1 * apiClient.executeQuery(connection, "SELECT * FROM Preferences") >> [qboPrefs]

        and: "preferences are mapped"
            result.size() == 1
            result[0].defaultCurrency != null
            result[0].multicurrencyEnabled == true
    }

    def "pullBatch should return empty list when API returns null"() {
        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then: "API returns null"
            1 * apiClient.executeQuery(connection, _) >> null

        and: "empty list returned"
            result.isEmpty()
    }

    def "pullBatch should return empty list when API returns empty list"() {
        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then: "API returns empty"
            1 * apiClient.executeQuery(connection, _) >> []

        and: "empty list returned"
            result.isEmpty()
    }

    def "pullBatch should handle null currencyPrefs"() {
        given: "QBO returns preferences without currency prefs"
            def qboPrefs = new Preferences()

        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then:
            1 * apiClient.executeQuery(connection, _) >> [qboPrefs]

        and: "preferences returned with null values"
            result.size() == 1
            result[0].defaultCurrency == null
            result[0].multicurrencyEnabled == null
    }

    def "pullBatch should handle null homeCurrency value"() {
        given: "QBO returns currency prefs with null homeCurrency"
            def currencyPrefs = new CurrencyPrefs()
            currencyPrefs.homeCurrency = null
            currencyPrefs.multiCurrencyEnabled = false

            def qboPrefs = new Preferences()
            qboPrefs.currencyPrefs = currencyPrefs

        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then:
            1 * apiClient.executeQuery(connection, _) >> [qboPrefs]

        and: "currency is null but multicurrency is set"
            result.size() == 1
            result[0].defaultCurrency == null
            result[0].multicurrencyEnabled == false
    }

    def "pullBatch should handle homeCurrency with null value"() {
        given: "QBO returns homeCurrency ref but value is null"
            def homeRef = new ReferenceType()
            homeRef.value = null

            def currencyPrefs = new CurrencyPrefs()
            currencyPrefs.homeCurrency = homeRef

            def qboPrefs = new Preferences()
            qboPrefs.currencyPrefs = currencyPrefs

        when: "pulling preferences"
            def result = provider.pullBatch(connection)

        then:
            1 * apiClient.executeQuery(connection, _) >> [qboPrefs]

        and: "currency is null"
            result.size() == 1
            result[0].defaultCurrency == null
    }

    def "getById should delegate to pullBatch"() {
        given: "QBO returns preferences"
            def currencyPrefs = new CurrencyPrefs()
            def homeRef = new ReferenceType()
            homeRef.value = "USD"
            currencyPrefs.homeCurrency = homeRef

            def qboPrefs = new Preferences()
            qboPrefs.currencyPrefs = currencyPrefs

        when: "getting by id"
            def result = provider.getById("any-id", connection)

        then:
            1 * apiClient.executeQuery(connection, _) >> [qboPrefs]

        and: "returns first preferences"
            result != null
            result.defaultCurrency != null
    }

    def "getById should return null when pullBatch returns empty"() {
        when: "getting by id with empty result"
            def result = provider.getById("any-id", connection)

        then:
            1 * apiClient.executeQuery(connection, _) >> []

        and: "null returned"
            result == null
    }
}
