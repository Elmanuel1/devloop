package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for RDS IAM database authentication.
 * <p>
 * When enabled, the application will use IAM authentication instead of password-based authentication.
 * This requires:
 * - RDS instance with IAM authentication enabled
 * - EC2 instance profile with rds-db:connect permission
 * - Database user granted the rds_iam role
 * <p>
 * Tokens expire after 15 minutes, so HikariCP max-lifetime must be configured accordingly.
 */
@ConfigurationProperties(prefix = "aws.rds")
@Data
@Validated
public class RdsIamProperties {

    /**
     * Enable IAM database authentication.
     * When false, standard password authentication is used.
     */
    private boolean iamAuthEnabled = false;

    /**
     * AWS region for RDS.
     */
    private String region;
}
