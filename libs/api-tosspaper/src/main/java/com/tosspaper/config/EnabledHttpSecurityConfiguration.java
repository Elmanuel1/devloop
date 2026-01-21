package com.tosspaper.config;

import com.tosspaper.httpsecurity.configurers.StatelessHttpSecurityConfigurer;
import com.tosspaper.httpsecurity.configurers.*;
import com.tosspaper.models.properties.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConditionalOnProperty(
        value = {"http.security.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class EnabledHttpSecurityConfiguration {

    @Bean
    @Order(10)
    public AllowedCorsDomainsHttpSecurityConfigurer disabledCorsDomainHttpSecurityConfigurer(
            AllowedCorsDomainsConfigurationProperties properties) {
        return new AllowedCorsDomainsHttpSecurityConfigurer(properties.getDomains());
    }

    @Bean
    @Order(11)
    public InsecurePathHttpSecurityConfigurer insecurePathHttpSecurityConfigurer(
            InsecurePathConfigurationProperties properties) {
        return new InsecurePathHttpSecurityConfigurer(properties.getPaths());
    }

    @Bean
    @Order(25)
    public StatelessHttpSecurityConfigurer statelessHttpSecurityConfigurer() {
        return new StatelessHttpSecurityConfigurer();
    }

    @Bean
    @Order(28)
    @ConditionalOnProperty(
            value = {"http.security.csrf.enabled"},
            havingValue = "false")
    public DisabledCsrfHttpSecurityConfigurer disabledCsrfHttpSecurityConfigurer() {
        return new DisabledCsrfHttpSecurityConfigurer();
    }

    @Bean
    @Order(28)
    public JWTHttpSecurityConfigurer jwtHttpSecurityConfigurer(
            JWTTokenProperties properties, 
            JwtClaimProperties jwtClaimProperties, 
            RestTemplate restTemplate,
            ObservationRegistry observationRegistry, 
            Cache jwkCache) {
        return new JWTHttpSecurityConfigurer(properties, jwtClaimProperties, restTemplate, observationRegistry, jwkCache);
    }

    @Bean
    @Order(29)
    public ExceptionHandlerfHttpSecurityConfigurer exceptionHandlerfHttpSecurityConfigurer() {
        return new ExceptionHandlerfHttpSecurityConfigurer();
    }

    @Bean
    @Order(30)
    public AuthorizedHttpSecurityConfigurer authorizedHttpSecurityConfigurer(
            AuthenticatedAccessConfigProperties properties) {
        return new AuthorizedHttpSecurityConfigurer(properties);
    }

    @Bean
    @Order(31)
    @ConditionalOnProperty(
            value = {"http.security.csrf.enabled"},
            havingValue = "true")
    public EnabledCsrfTokenHttpSecurityConfigurer enableCsrfTokenHttpSecurityConfigurer(
            IgnoredCsrfPathConfigurationProperties ignoredPathProperties,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository) {
        return new EnabledCsrfTokenHttpSecurityConfigurer(
                ignoredPathProperties.getPaths(), csrfTokenRepository, sessionAuthenticationStrategy);
    }
}
