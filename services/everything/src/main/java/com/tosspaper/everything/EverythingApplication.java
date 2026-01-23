package com.tosspaper.everything;

import com.tosspaper.models.properties.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.tosspaper.aiengine.properties.AIProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({
    RedisStreamsProperties.class,
    AwsProperties.class,
    FileProperties.class,
    AIProperties.class,
    com.tosspaper.aiengine.properties.HttpProperties.class,
    InsecurePathConfigurationProperties.class,
    AllowedCorsDomainsConfigurationProperties.class,
    IgnoredCsrfPathConfigurationProperties.class,
    JWTTokenProperties.class,
    JwkCacheProperties.class,
    AuthenticatedAccessConfigProperties.class,
    JwtClaimProperties.class,
    CsrfCookieProperties.class,
    com.tosspaper.models.config.MailgunProperties.class,
    com.tosspaper.models.config.AppEmailProperties.class
})
@ComponentScan(basePackages = "com.tosspaper")
public class EverythingApplication {

    public static void main(String[] args) {
        SpringApplication.run(EverythingApplication.class, args);
    }
}
