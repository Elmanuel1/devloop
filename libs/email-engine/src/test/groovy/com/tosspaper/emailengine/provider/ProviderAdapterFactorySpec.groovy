package com.tosspaper.emailengine.provider

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Subject

class ProviderAdapterFactorySpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ProviderAdapterFactory factory

    def setup() {
        factory = new ProviderAdapterFactory(objectMapper)
    }

    def "should return CloudflareAdapterImpl for cloudflare provider"() {
        when:
        def adapter = factory.getAdapter("cloudflare")

        then:
        adapter != null
        adapter.getProviderName() == "cloudflare"
    }

    def "should return MailGunAdapterImpl for mailgun provider"() {
        when:
        def adapter = factory.getAdapter("mailgun")

        then:
        adapter != null
        adapter.getProviderName() == "mailgun"
    }

    def "should throw exception for unknown provider"() {
        when:
        factory.getAdapter("unknown-provider")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("No email provider found")
    }

    def "should be case sensitive for provider names"() {
        when:
        factory.getAdapter("CLOUDFLARE")

        then:
        thrown(IllegalArgumentException)
    }

    def "InboundEmailProvider enum should have correct values"() {
        expect:
        ProviderAdapterFactory.InboundEmailProvider.CLOUDFLARE.getName() == "cloudflare"
        ProviderAdapterFactory.InboundEmailProvider.MAILGUN.getName() == "mailgun"
    }

    def "InboundEmailProvider.from should return correct enum for valid provider"() {
        when:
        def provider = ProviderAdapterFactory.InboundEmailProvider.from("cloudflare")

        then:
        provider == ProviderAdapterFactory.InboundEmailProvider.CLOUDFLARE
    }

    def "InboundEmailProvider.from should throw exception for invalid provider"() {
        when:
        ProviderAdapterFactory.InboundEmailProvider.from("invalid")

        then:
        thrown(IllegalArgumentException)
    }
}
