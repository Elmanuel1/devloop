package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import io.temporal.serviceclient.WorkflowServiceStubs
import spock.lang.Specification
import spock.lang.Subject

class IntegrationScheduleManagerSpec extends Specification {

    WorkflowServiceStubs workflowServiceStubs = Mock()
    QuickBooksProperties quickBooksProperties = Mock()

    @Subject
    IntegrationScheduleManager manager

    def setup() {
        manager = new IntegrationScheduleManager(workflowServiceStubs, quickBooksProperties)
    }

    def makeConnection(String id = "a1b2c3d4-1234-5678-9012-abcdef123456") {
        IntegrationConnection.builder()
            .id(id)
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .externalCompanyName("Acme Corp")
            .realmId("realm-123")
            .build()
    }

    // ==================== createSchedule Tests ====================

    def "should attempt to create schedule and handle ScheduleClient initialization failure"() {
        given: "a valid connection and sync config"
            def connection = makeConnection()
            def syncConfig = new QuickBooksProperties.Sync()
            syncConfig.syncIntervalSeconds = 1800
            quickBooksProperties.getSync() >> syncConfig

        when: "creating schedule"
            manager.createSchedule(connection)

        then: "exception is wrapped and rethrown since ScheduleClient.newInstance uses real stubs"
            thrown(RuntimeException)
    }

    // ==================== pauseSchedule Tests ====================

    def "should not throw when pause schedule encounters exception"() {
        given: "a connection"
            def connection = makeConnection()

        when: "pausing schedule (will fail because ScheduleClient.newInstance requires real stubs)"
            manager.pauseSchedule(connection)

        then: "exception is caught and logged - method does not throw"
            notThrown(Exception)
    }

    // ==================== deleteSchedule Tests ====================

    def "should not throw when delete schedule encounters exception"() {
        given: "a connection"
            def connection = makeConnection()

        when: "deleting schedule (will fail because ScheduleClient.newInstance)"
            manager.deleteSchedule(connection)

        then: "exception is caught - method does not throw"
            notThrown(Exception)
    }

    // ==================== unpauseSchedule Tests ====================

    def "should handle unpause and rethrow non-NOT_FOUND errors"() {
        given: "a connection"
            def connection = makeConnection()
            def syncConfig = new QuickBooksProperties.Sync()
            syncConfig.syncIntervalSeconds = 1800
            quickBooksProperties.getSync() >> syncConfig

        when: "unpausing schedule (will throw because ScheduleClient.newInstance needs real stubs)"
            manager.unpauseSchedule(connection)

        then: "exception is rethrown as RuntimeException"
            thrown(RuntimeException)
    }
}
