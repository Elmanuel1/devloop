package com.tosspaper.emailengine

import com.tosspaper.models.service.EmailDomainService
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import spock.mock.DetachedMockFactory

/**
 * Test-only Spring Boot application for repository integration tests.
 *
 * Scans only the repository package needed for database tests.
 * JOOQ auto-configuration provides the DSLContext bean automatically.
 * Flyway auto-configuration runs migrations against the test database.
 * Redis and Redisson auto-configurations are excluded (no Redis needed for DB tests).
 * A mock EmailDomainService is provided for ApprovedSenderRepositoryImpl.
 */
@SpringBootApplication(
    exclude = [
        RedisAutoConfiguration,
        RedisRepositoriesAutoConfiguration
    ],
    excludeName = [
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
    ]
)
@ComponentScan(
    basePackages = [
        "com.tosspaper.emailengine.repository"
    ]
)
class TestApplication {

    private static final DetachedMockFactory factory = new DetachedMockFactory()

    static void main(String[] args) {
        SpringApplication.run(TestApplication, args)
    }

    @Bean
    EmailDomainService emailDomainService() {
        return factory.Mock(EmailDomainService)
    }
}
