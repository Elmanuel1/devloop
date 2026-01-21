package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

@Slf4j
public class StatelessHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            log.debug("Creating a stateless session");
            http.sessionManagement(httpSecuritySessionManagementConfigurer ->
                    httpSecuritySessionManagementConfigurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error creating a stateless session", e);
        }
    }
}
