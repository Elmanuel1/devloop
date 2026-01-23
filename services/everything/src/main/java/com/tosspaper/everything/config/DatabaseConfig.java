package com.tosspaper.everything.config;

import com.tosspaper.models.datasource.RdsIamHikariDataSource;
import com.tosspaper.models.properties.RdsIamProperties;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Database configuration with conditional IAM authentication support.
 *
 * This configuration provides two DataSource beans:
 * - Standard password-based authentication (default, for local development)
 * - IAM-based authentication (production, when aws.rds.iam-auth-enabled=true)
 *
 * IAM authentication requirements:
 * - RDS instance must have IAM authentication enabled
 * - IAM role with rds-db:connect permission
 * - Database user with rds_iam role granted
 * - SSL/TLS connection required
 * - HikariCP max-lifetime < 15 minutes (token lifetime)
 */
@Configuration
@EnableConfigurationProperties({RdsIamProperties.class, DataSourceProperties.class})
@Slf4j
public class DatabaseConfig {

    private final RdsIamProperties rdsIamProperties;

    public DatabaseConfig(RdsIamProperties rdsIamProperties) {
        this.rdsIamProperties = rdsIamProperties;
    }

    /**
     * Creates the appropriate DataSource based on IAM authentication configuration.
     * When IAM auth is enabled, creates RdsIamHikariDataSource; otherwise uses standard HikariDataSource.
     *
     * HikariCP settings are automatically bound via @ConfigurationProperties on the returned datasource.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        if (rdsIamProperties.isIamAuthEnabled()) {
            return createIamDataSource(properties);
        } else {
            return createStandardDataSource(properties);
        }
    }

    /**
     * Creates a standard HikariDataSource with password authentication.
     * Used for local development and non-IAM environments.
     */
    private HikariDataSource createStandardDataSource(DataSourceProperties properties) {
        log.info("Creating standard HikariDataSource with password authentication");

        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        log.info("Standard HikariDataSource configured - URL: {}, User: {}",
                properties.getUrl(), properties.getUsername());
        return dataSource;
    }

    /**
     * Creates an RDS IAM HikariDataSource with IAM authentication.
     * Used for production environments with IAM database authentication.
     */
    private RdsIamHikariDataSource createIamDataSource(DataSourceProperties properties) {
        log.info("Creating RDS IAM HikariDataSource with instance profile credentials");

        String region = rdsIamProperties.getRegion();

        if (region == null || region.isBlank()) {
            throw new IllegalStateException("RDS IAM authentication is enabled but aws.rds.region is not configured");
        }

        RdsIamHikariDataSource dataSource = new RdsIamHikariDataSource(
                region,
                properties.getUrl(),
                properties.getUsername()
        );

        // Set driver class name (HikariCP settings are bound via @ConfigurationProperties)
        dataSource.setDriverClassName(properties.getDriverClassName());

        log.info("RDS IAM HikariDataSource configured - URL: {}, User: {}, Region: {}",
                properties.getUrl(), properties.getUsername(), region);
        return dataSource;
    }
}
