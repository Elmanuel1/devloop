package com.tosspaper.integrations.temporal

import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification

import java.time.OffsetDateTime

class SyncConnectionDataSpec extends Specification {

    def "from should copy relevant fields from IntegrationConnection"() {
        given: "a fully populated connection"
            def now = OffsetDateTime.now()
            def syncFrom = now.minusDays(7)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("secret-token")
                .refreshToken("secret-refresh")
                .expiresAt(now.plusHours(1))
                .realmId("realm-123")
                .lastSyncAt(now.minusHours(2))
                .syncFrom(syncFrom)
                .build()

        when: "creating SyncConnectionData from connection"
            def data = SyncConnectionData.from(connection)

        then: "non-sensitive fields are copied"
            data.id == "conn-1"
            data.companyId == 100L
            data.provider == IntegrationProvider.QUICKBOOKS
            data.expiresAt == now.plusHours(1)
            data.realmId == "realm-123"
            data.lastSyncAt == now.minusHours(2)
            data.syncFrom == syncFrom
    }

    def "from should handle null optional fields"() {
        given: "a connection with minimal fields"
            def connection = IntegrationConnection.builder()
                .id("conn-2")
                .companyId(200L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "creating SyncConnectionData from connection"
            def data = SyncConnectionData.from(connection)

        then: "nullable fields are null"
            data.id == "conn-2"
            data.companyId == 200L
            data.provider == IntegrationProvider.QUICKBOOKS
            data.expiresAt == null
            data.realmId == null
            data.lastSyncAt == null
            data.syncFrom == null
    }

    def "no-arg constructor should create empty instance"() {
        when: "using no-arg constructor"
            def data = new SyncConnectionData()

        then: "all fields are null"
            data.id == null
            data.companyId == null
            data.provider == null
    }

    def "all-arg constructor should set all fields"() {
        given: "all arguments"
            def now = OffsetDateTime.now()

        when: "using all-arg constructor"
            def data = new SyncConnectionData("id-1", 42L, IntegrationProvider.QUICKBOOKS, now, "realm", now.minusDays(1), now.minusDays(7))

        then: "all fields are set"
            data.id == "id-1"
            data.companyId == 42L
            data.provider == IntegrationProvider.QUICKBOOKS
            data.expiresAt == now
            data.realmId == "realm"
            data.lastSyncAt == now.minusDays(1)
            data.syncFrom == now.minusDays(7)
    }

    def "setters should update fields"() {
        given: "empty instance"
            def data = new SyncConnectionData()
            def now = OffsetDateTime.now()

        when: "setting fields via setters"
            data.setId("test-id")
            data.setCompanyId(99L)
            data.setProvider(IntegrationProvider.QUICKBOOKS)
            data.setExpiresAt(now)
            data.setRealmId("test-realm")
            data.setLastSyncAt(now.minusHours(1))
            data.setSyncFrom(now.minusDays(3))

        then: "fields are updated"
            data.id == "test-id"
            data.companyId == 99L
            data.provider == IntegrationProvider.QUICKBOOKS
            data.expiresAt == now
            data.realmId == "test-realm"
            data.lastSyncAt == now.minusHours(1)
            data.syncFrom == now.minusDays(3)
    }
}
