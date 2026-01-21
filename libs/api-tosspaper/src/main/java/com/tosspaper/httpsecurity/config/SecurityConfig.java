package com.tosspaper.httpsecurity.config;

import com.tosspaper.httpsecurity.configurers.HttpSecurityConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.ForceEagerSessionCreationFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;

@Configuration
@Order(1)
@ConditionalOnProperty(
        value = {"http.security.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, List<HttpSecurityConfigurer> httpSecurityConfigurers)
            throws Exception {
        httpSecurityConfigurers = httpSecurityConfigurers.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();

        for (HttpSecurityConfigurer httpSecurityConfigurer : httpSecurityConfigurers) {
            httpSecurityConfigurer.configure(http);
        }

        return http.addFilterBefore(new ForwardedHeaderFilter(), ForceEagerSessionCreationFilter.class)
                .build();
    }
}
