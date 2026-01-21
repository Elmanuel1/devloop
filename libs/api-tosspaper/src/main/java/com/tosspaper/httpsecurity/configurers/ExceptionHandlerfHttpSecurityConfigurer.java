package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.ExtendedBearerTokenAuthenticationEntryPoint;
import com.tosspaper.httpsecurity.GlobalAccessDeniedHandler;
import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Slf4j
@RequiredArgsConstructor
public class ExceptionHandlerfHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            log.debug("Applying forbidden access exception handler for all endpoints");
            http.exceptionHandling(c -> c.authenticationEntryPoint(new ExtendedBearerTokenAuthenticationEntryPoint())
                    .accessDeniedHandler(new GlobalAccessDeniedHandler()));
        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error creating a stateless session", e);
        }
    }
}
