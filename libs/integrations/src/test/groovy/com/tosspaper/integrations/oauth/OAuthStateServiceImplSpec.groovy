package com.tosspaper.integrations.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.integrations.common.exception.IntegrationAuthException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration

/**
 * Comprehensive tests for OAuthStateServiceImpl.
 * Tests OAuth state storage and validation using Redis.
 */
class OAuthStateServiceImplSpec extends Specification {

    StringRedisTemplate redisTemplate = Mock()
    ValueOperations<String, String> valueOps = Mock()
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    OAuthStateServiceImpl service = new OAuthStateServiceImpl(
        redisTemplate,
        objectMapper
    )

    def setup() {
        redisTemplate.opsForValue() >> valueOps
    }

    def "storeState should store state in Redis with TTL"() {
        given:
        def state = "state-123"
        def companyId = 100L
        def providerId = "quickbooks"

        when:
        service.storeState(state, companyId, providerId)

        then:
        1 * valueOps.set("oauth:state:state-123", _, Duration.ofMinutes(10)) >> { String key, String value, Duration ttl ->
            def data = objectMapper.readValue(value, Map)
            assert data.companyId == "100"
            assert data.providerId == "quickbooks"
        }
    }

    def "validateAndConsumeState should return state data when valid"() {
        given:
        def state = "state-456"
        def stateData = objectMapper.writeValueAsString([
            companyId: "200",
            providerId: "quickbooks"
        ])

        when:
        def result = service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-456") >> stateData
        result.companyId() == 200L
        result.providerId() == "quickbooks"
    }

    def "validateAndConsumeState should throw exception when state not found"() {
        given:
        def state = "invalid-state"

        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:invalid-state") >> null
        def ex = thrown(IntegrationAuthException)
        ex.errorCode == "not_found_or_expired"
    }

    def "validateAndConsumeState should throw exception for invalid JSON format"() {
        given:
        def state = "state-789"

        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-789") >> "{ invalid json }"
        def ex = thrown(IntegrationAuthException)
        ex.errorCode == "invalid_state_format"
    }

    def "validateAndConsumeState should throw exception for missing companyId"() {
        given:
        def state = "state-999"
        def stateData = objectMapper.writeValueAsString([
            providerId: "quickbooks"
        ])

        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-999") >> stateData
        thrown(IntegrationAuthException)
    }

    def "validateAndConsumeState should throw exception for invalid companyId format"() {
        given:
        def state = "state-abc"
        def stateData = objectMapper.writeValueAsString([
            companyId: "not-a-number",
            providerId: "quickbooks"
        ])

        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-abc") >> stateData
        def ex = thrown(IntegrationAuthException)
        ex.errorCode == "invalid_state_format"
    }

    def "storeState should throw exception on Redis error"() {
        given:
        def state = "state-error"
        def companyId = 100L
        def providerId = "quickbooks"

        when:
        service.storeState(state, companyId, providerId)

        then:
        1 * valueOps.set(_, _, _) >> { throw new RuntimeException("Redis error") }
        thrown(IntegrationAuthException)
    }

    def "validateAndConsumeState should consume state on successful validation"() {
        given:
        def state = "state-consume"
        def stateData = objectMapper.writeValueAsString([
            companyId: "100",
            providerId: "quickbooks"
        ])

        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-consume") >> stateData

        and: "state should not be retrievable again"
        when:
        service.validateAndConsumeState(state)

        then:
        1 * valueOps.getAndDelete("oauth:state:state-consume") >> null
        thrown(IntegrationAuthException)
    }
}
