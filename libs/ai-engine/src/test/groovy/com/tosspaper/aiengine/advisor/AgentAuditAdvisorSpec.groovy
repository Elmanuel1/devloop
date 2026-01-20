package com.tosspaper.aiengine.advisor

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for AgentAuditAdvisor.
 * Note: Complex integration tests with Spring AI AdvisedRequest are skipped
 * because AdvisedRequest is a final class that's difficult to mock.
 */
class AgentAuditAdvisorSpec extends Specification {

    VirtualFilesystemService vfsService
    ObjectMapper objectMapper

    @Subject
    AgentAuditAdvisor advisor

    def setup() {
        vfsService = Mock()
        objectMapper = new ObjectMapper()
        advisor = new AgentAuditAdvisor(vfsService, objectMapper)
    }

    def "should have correct name"() {
        expect:
        advisor.getName() == "AgentAuditAdvisor"
    }

    def "should have low precedence order"() {
        expect:
        advisor.getOrder() == org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100
    }
}
