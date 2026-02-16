package com.tosspaper.emailengine.repository

import com.tosspaper.emailengine.TestApplication
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Specification

import static com.tosspaper.models.jooq.Tables.COMPANIES

/**
 * Base class for repository integration tests.
 * Uses TestContainers to manage a PostgreSQL container with pgvector support.
 */
@SpringBootTest(classes = TestApplication)
@ActiveProfiles("test")
abstract class BaseRepositoryTest extends Specification {

    private static volatile boolean testCompanyCreated = false

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("tosspaper")
        .withUsername("postgres")
        .withPassword("postgres")
        .withCommand("postgres", "-c", "shared_preload_libraries=vector")

    static {
        postgres.start()
    }

    @Autowired
    DSLContext baseDsl

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
    }

    def setup() {
        ensureTestCompanyExists()
    }

    private void ensureTestCompanyExists() {
        if (!testCompanyCreated) {
            def exists = baseDsl.selectCount()
                .from(COMPANIES)
                .where(COMPANIES.ID.eq(TestDataFactory.TEST_COMPANY_ID))
                .fetchOne(0, int.class) > 0

            if (!exists) {
                baseDsl.insertInto(COMPANIES)
                    .set(COMPANIES.ID, TestDataFactory.TEST_COMPANY_ID)
                    .set(COMPANIES.NAME, "Test Company")
                    .set(COMPANIES.EMAIL, "test@testcompany.com")
                    .onConflict(COMPANIES.ID)
                    .doNothing()
                    .execute()
            }
            testCompanyCreated = true
        }
    }
}
