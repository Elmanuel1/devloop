package com.tosspaper.emailengine.service

import com.tosspaper.models.service.SyncConflictNotificationRequest
import spock.lang.Specification
import spock.lang.Subject

class SyncConflictEmailContentBuilderSpec extends Specification {

    @Subject
    SyncConflictEmailContentBuilder builder = new SyncConflictEmailContentBuilder()

    def "should build subject with entity details"() {
        when:
        def subject = builder.buildSubject("Acme Corp", "QuickBooks", "Invoice", "INV-001")

        then:
        subject == "Sync Conflict: Invoice 'INV-001' in QuickBooks"
    }

    def "should build body with all details"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L,
            "QuickBooks",
            "Invoice",
            "INV-001",
            "Version conflict detected",
            "user@example.com"
        )

        when:
        def body = builder.buildBody("Acme Corp", request)

        then:
        body.contains("sync conflict was detected")
        body.contains("Invoice 'INV-001'")
        body.contains("QuickBooks")
        body.contains("Version conflict detected")
        body.contains("pulled the latest data")
        body.contains("Review the updated entity")
    }

    def "should include provider name in body"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L, "Xero", "Bill", "BILL-001", "Conflict", "user@example.com"
        )

        when:
        def body = builder.buildBody("Company", request)

        then:
        body.contains("Xero")
        body.count("Xero") == 3
    }
}
