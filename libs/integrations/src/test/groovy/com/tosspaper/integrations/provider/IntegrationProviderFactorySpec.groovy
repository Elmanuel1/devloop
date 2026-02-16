package com.tosspaper.integrations.provider

import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for IntegrationProviderFactory.
 * Tests provider resolution and factory initialization.
 */
class IntegrationProviderFactorySpec extends Specification {

    def oauthProvider = Mock(IntegrationOAuthProvider) {
        getProviderId() >> IntegrationProvider.QUICKBOOKS
        getDisplayName() >> "QuickBooks"
    }

    def companyInfoProvider = Mock(IntegrationCompanyInfoProvider) {
        getProviderId() >> IntegrationProvider.QUICKBOOKS
    }

    def pushProvider = Mock(IntegrationPushProvider) {
        getProviderId() >> IntegrationProvider.QUICKBOOKS
        getEntityType() >> IntegrationEntityType.VENDOR
    }

    def pullProvider = Mock(IntegrationPullProvider) {
        getProviderId() >> IntegrationProvider.QUICKBOOKS
        getEntityType() >> IntegrationEntityType.VENDOR
    }

    @Subject
    IntegrationProviderFactory factory

    def setup() {
        factory = new IntegrationProviderFactory(
            [oauthProvider],
            [companyInfoProvider],
            [pushProvider],
            [pullProvider]
        )
    }

    def "getOAuthProvider should return provider when found"() {
        when:
        def result = factory.getOAuthProvider(IntegrationProvider.QUICKBOOKS)

        then:
        result == oauthProvider
    }

    def "getOAuthProvider should throw exception when not found"() {
        when:
        factory.getOAuthProvider(IntegrationProvider.XERO)

        then:
        thrown(IllegalArgumentException)
    }

    def "getCompanyInfoProvider should return provider when found"() {
        when:
        def result = factory.getCompanyInfoProvider(IntegrationProvider.QUICKBOOKS)

        then:
        result.isPresent()
        result.get() == companyInfoProvider
    }

    def "getCompanyInfoProvider should return empty when not found"() {
        when:
        def result = factory.getCompanyInfoProvider(IntegrationProvider.XERO)

        then:
        result.isEmpty()
    }

    def "getPushProvider should return provider for matching provider and entity type"() {
        when:
        def result = factory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR)

        then:
        result.isPresent()
        result.get() == pushProvider
    }

    def "getPushProvider should return empty for non-matching provider"() {
        when:
        def result = factory.getPushProvider(IntegrationProvider.XERO, IntegrationEntityType.VENDOR)

        then:
        result.isEmpty()
    }

    def "getPushProvider should return empty for non-matching entity type"() {
        when:
        def result = factory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM)

        then:
        result.isEmpty()
    }

    def "getPullProvider should return provider for matching provider and entity type"() {
        when:
        def result = factory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR)

        then:
        result.isPresent()
        result.get() == pullProvider
    }

    def "getPullProvider should return empty for non-matching provider"() {
        when:
        def result = factory.getPullProvider(IntegrationProvider.XERO, IntegrationEntityType.VENDOR)

        then:
        result.isEmpty()
    }

    def "getAllProviders should return list of provider info"() {
        when:
        def result = factory.getAllProviders()

        then:
        result.size() == 1
        result[0].id() == "quickbooks"
        result[0].displayName() == "QuickBooks"
    }

    def "factory should handle multiple providers"() {
        given:
        def oauthProvider1 = Mock(IntegrationOAuthProvider) {
            getProviderId() >> IntegrationProvider.QUICKBOOKS
            getDisplayName() >> "QuickBooks"
        }
        def oauthProvider2 = Mock(IntegrationOAuthProvider) {
            getProviderId() >> IntegrationProvider.XERO
            getDisplayName() >> "Xero"
        }

        def multiFactory = new IntegrationProviderFactory(
            [oauthProvider1, oauthProvider2],
            [],
            [],
            []
        )

        when:
        def providers = multiFactory.getAllProviders()

        then:
        providers.size() == 2
    }

    def "factory should handle multiple entity types per provider"() {
        given:
        def pushVendor = Mock(IntegrationPushProvider) {
            getProviderId() >> IntegrationProvider.QUICKBOOKS
            getEntityType() >> IntegrationEntityType.VENDOR
        }
        def pushItem = Mock(IntegrationPushProvider) {
            getProviderId() >> IntegrationProvider.QUICKBOOKS
            getEntityType() >> IntegrationEntityType.ITEM
        }

        def multiFactory = new IntegrationProviderFactory(
            [],
            [],
            [pushVendor, pushItem],
            []
        )

        when:
        def vendorProvider = multiFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR)
        def itemProvider = multiFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM)

        then:
        vendorProvider.isPresent()
        itemProvider.isPresent()
        vendorProvider.get() == pushVendor
        itemProvider.get() == pushItem
    }
}
