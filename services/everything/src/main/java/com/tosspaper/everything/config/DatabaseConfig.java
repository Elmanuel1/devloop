package com.tosspaper.everything.config;

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
 * Database configuration using AWS Advanced JDBC Wrapper.
 *
 * The AWS wrapper handles IAM authentication automatically when the URL contains
 * wrapperPlugins=iam. For local dev (no IAM), it works as a standard JDBC driver.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@Slf4j
public class DatabaseConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        log.info("Creating HikariDataSource with driver: {}", properties.getDriverClassName());

        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        log.info("DataSource configured - URL: {}, User: {}",
                properties.getUrl(), properties.getUsername());

        return dataSource;
    }
}
