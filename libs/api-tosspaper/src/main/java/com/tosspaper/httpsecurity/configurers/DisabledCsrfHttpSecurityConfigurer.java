package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

@Slf4j
public class DisabledCsrfHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            log.warn("Disabling CSRF for all endpoints");
            http.csrf(AbstractHttpConfigurer::disable);
        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error disabling CSRF protection", e);
        }
    }
}
