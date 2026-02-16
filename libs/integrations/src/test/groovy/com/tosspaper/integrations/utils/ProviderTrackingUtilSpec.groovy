package com.tosspaper.integrations.utils

import com.tosspaper.models.domain.Party
import spock.lang.Specification

class ProviderTrackingUtilSpec extends Specification {

    // ==================== addMetadata Tests ====================

    def "addMetadata should add value to new metadata map when null"() {
        given: "an entity with no metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = null

        when: "adding metadata"
            ProviderTrackingUtil.addMetadata(entity, "balance", "100.00")

        then: "metadata is created and populated"
            entity.externalMetadata != null
            entity.externalMetadata["balance"] == "100.00"
    }

    def "addMetadata should add value to existing metadata map"() {
        given: "an entity with existing metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["existing": "value"]

        when: "adding metadata"
            ProviderTrackingUtil.addMetadata(entity, "balance", "200.00")

        then: "both entries exist"
            entity.externalMetadata["existing"] == "value"
            entity.externalMetadata["balance"] == "200.00"
    }

    def "addMetadata should ignore null values"() {
        given: "an entity"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = null

        when: "adding null value"
            ProviderTrackingUtil.addMetadata(entity, "key", null)

        then: "metadata is not created"
            entity.externalMetadata == null
    }

    // ==================== getMetadata Tests ====================

    def "getMetadata should return value for existing key"() {
        given: "an entity with metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["balance": "100.00"]

        when: "getting metadata"
            def value = ProviderTrackingUtil.getMetadata(entity, "balance")

        then: "value is returned"
            value == "100.00"
    }

    def "getMetadata should return null for missing key"() {
        given: "an entity with metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["balance": "100.00"]

        when: "getting non-existent key"
            def value = ProviderTrackingUtil.getMetadata(entity, "nonexistent")

        then: "null returned"
            value == null
    }

    def "getMetadata should return null when metadata map is null"() {
        given: "an entity with null metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = null

        when: "getting metadata"
            def value = ProviderTrackingUtil.getMetadata(entity, "key")

        then: "null returned"
            value == null
    }

    // ==================== getMetadata (typed) Tests ====================

    def "getMetadata typed should return value when type matches"() {
        given: "an entity with String metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["name": "ACME"]

        when: "getting typed metadata"
            def value = ProviderTrackingUtil.getMetadata(entity, "name", String.class)

        then: "typed value returned"
            value == "ACME"
    }

    def "getMetadata typed should return null when type does not match"() {
        given: "an entity with String metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["name": "ACME"]

        when: "getting wrong type"
            def value = ProviderTrackingUtil.getMetadata(entity, "name", Integer.class)

        then: "null returned"
            value == null
    }

    def "getMetadata typed should return null when key not found"() {
        given: "an entity with metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = ["name": "ACME"]

        when: "getting non-existent key"
            def value = ProviderTrackingUtil.getMetadata(entity, "nonexistent", String.class)

        then: "null returned"
            value == null
    }

    def "getMetadata typed should return null when metadata is null"() {
        given: "an entity with null metadata"
            def entity = Party.builder().name("Test").build()
            entity.externalMetadata = null

        when: "getting typed metadata"
            def value = ProviderTrackingUtil.getMetadata(entity, "key", String.class)

        then: "null returned"
            value == null
    }
}
