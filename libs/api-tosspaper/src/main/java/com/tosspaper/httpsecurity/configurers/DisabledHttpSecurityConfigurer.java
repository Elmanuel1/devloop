package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@RequiredArgsConstructor
@Slf4j
public class DisabledHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {

        try {
            log.warn("Disabling security for all endpoints");
            http.authorizeHttpRequests(
                    authorizeHttpRequests -> authorizeHttpRequests.anyRequest().permitAll());

        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error configuring authorization for all endpoints", e);
        }
    }
}
