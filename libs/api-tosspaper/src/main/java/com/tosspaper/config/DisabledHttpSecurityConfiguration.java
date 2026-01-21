package com.tosspaper.config;

import com.tosspaper.httpsecurity.configurers.DisabledHttpSecurityConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        value = {"http.security.enabled"},
        havingValue = "false")
public class DisabledHttpSecurityConfiguration {

    @Bean
    public DisabledHttpSecurityConfigurer allowedCorsDomainHttpSecurityConfigurer() {
        return new DisabledHttpSecurityConfigurer();
    }
}
