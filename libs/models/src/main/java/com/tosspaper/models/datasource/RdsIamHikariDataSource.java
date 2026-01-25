package com.tosspaper.models.datasource;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import java.net.URI;
import java.time.Instant;

/**
 * Custom HikariDataSource that generates IAM authentication tokens for RDS connections.
 *
 * This datasource:
 * - Uses DefaultCredentialsProvider (EC2 instance profile) to generate RDS authentication tokens
 * - Caches tokens and refreshes them before expiration (tokens valid for 15 minutes)
 * - Provides thread-safe token generation with double-checked locking
 *
 * Configuration requirements:
 * - EC2 instance profile must have rds-db:connect permission
 * - HikariCP max-lifetime must be less than 15 minutes (token lifetime)
 * - SSL/TLS is required for IAM authentication
 * - Database user must be granted the rds_iam role in PostgreSQL
 */
@Slf4j
public class RdsIamHikariDataSource extends HikariDataSource {

    private static final int TOKEN_EXPIRY_MINUTES = 15;
    private static final int TOKEN_REFRESH_BUFFER_MINUTES = 1;

    private final String region;
    private final String hostname;
    private final int port;
    private final String dbUsername;

    private final RdsUtilities rdsUtilities;

    private volatile String cachedToken;
    private volatile Instant tokenExpiryTime;
    private final Object tokenLock = new Object();

    /**
     * Creates a new RDS IAM HikariDataSource.
     *
     * @param region     AWS region
     * @param jdbcUrl    JDBC URL (must include hostname and port)
     * @param dbUsername Database username (IAM-enabled user)
     */
    public RdsIamHikariDataSource(String region, String jdbcUrl, String dbUsername) {
        super();
        // Set required HikariDataSource properties
        this.setJdbcUrl(jdbcUrl);
        this.setUsername(dbUsername);

        this.region = region;
        this.dbUsername = dbUsername;

        // Parse hostname and port from JDBC URL
        // Expected format: jdbc:postgresql://hostname:port/database?params
        HostPortPair hostPort = parseJdbcUrl(jdbcUrl);
        this.hostname = hostPort.hostname;
        this.port = hostPort.port;

        // Create RDS utilities with default credentials (EC2 instance profile)
        this.rdsUtilities = RdsUtilities.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .build();

        log.info("Initialized RDS IAM HikariDataSource - hostname: {}, port: {}, user: {}, region: {}",
                hostname, port, dbUsername, region);
    }

    /**
     * Overrides getPassword() to return a fresh IAM authentication token.
     * HikariCP calls this method when creating new connections.
     *
     * @return IAM authentication token
     */
    @Override
    public String getPassword() {
        return getAuthenticationToken();
    }

    /**
     * Gets a valid authentication token, generating a new one if expired or about to expire.
     * Uses double-checked locking for thread safety.
     *
     * @return valid IAM authentication token
     */
    private String getAuthenticationToken() {
        // Quick check without lock
        if (isTokenValid()) {
            return cachedToken;
        }

        // Double-checked locking for thread safety
        synchronized (tokenLock) {
            if (isTokenValid()) {
                return cachedToken;
            }

            return generateNewToken();
        }
    }

    /**
     * Checks if the cached token is still valid (not expired and has buffer time remaining).
     */
    private boolean isTokenValid() {
        if (cachedToken == null || tokenExpiryTime == null) {
            return false;
        }
        // Refresh token 1 minute before expiry
        return Instant.now().plusSeconds(TOKEN_REFRESH_BUFFER_MINUTES * 60L).isBefore(tokenExpiryTime);
    }

    /**
     * Generates a new IAM authentication token using the instance profile credentials.
     */
    private String generateNewToken() {
        log.info("Generating new RDS IAM authentication token for user '{}' at {}:{}", dbUsername, hostname, port);

        try {
            GenerateAuthenticationTokenRequest request = GenerateAuthenticationTokenRequest.builder()
                    .hostname(hostname)
                    .port(port)
                    .username(dbUsername)
                    .build();

            cachedToken = rdsUtilities.generateAuthenticationToken(request);
            tokenExpiryTime = Instant.now().plusSeconds(TOKEN_EXPIRY_MINUTES * 60L);

            log.info("Generated new RDS IAM authentication token, expires at {}", tokenExpiryTime);
            return cachedToken;

        } catch (Exception e) {
            log.error("Failed to generate RDS IAM authentication token: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate RDS IAM authentication token", e);
        }
    }

    /**
     * Parses hostname and port from a JDBC URL.
     *
     * @param jdbcUrl JDBC URL in format jdbc:postgresql://hostname:port/database
     * @return hostname and port pair
     */
    private HostPortPair parseJdbcUrl(String jdbcUrl) {
        try {
            // Remove jdbc: prefix and parse as URI
            String uriString = jdbcUrl.replace("jdbc:", "");
            URI uri = URI.create(uriString);

            String host = uri.getHost();
            int parsedPort = uri.getPort();

            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Could not parse hostname from JDBC URL: " + jdbcUrl);
            }

            // Default PostgreSQL port if not specified
            if (parsedPort <= 0) {
                parsedPort = 5432;
            }

            return new HostPortPair(host, parsedPort);

        } catch (Exception e) {
            log.error("Failed to parse JDBC URL: {}", jdbcUrl, e);
            throw new IllegalArgumentException("Invalid JDBC URL format: " + jdbcUrl, e);
        }
    }

    /**
     * Simple record for hostname and port pair.
     */
    private record HostPortPair(String hostname, int port) {}
}
