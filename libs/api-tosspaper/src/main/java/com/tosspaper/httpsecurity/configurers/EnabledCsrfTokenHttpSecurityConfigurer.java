package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class EnabledCsrfTokenHttpSecurityConfigurer implements HttpSecurityConfigurer {

    private final List<String> ignoredPaths;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            log.debug("Configuring csrf token http security");
            var handler = new CsrfTokenRequestAttributeHandler();
            handler.setCsrfRequestAttributeName(null);
            http.csrf(httpSecurityCsrfConfigurer1 -> {
                var configurer = httpSecurityCsrfConfigurer1
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(handler)
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy);
                ignoredPaths.forEach(path -> {
                    log.warn("ignoring csrf check to path {}", path);
                    configurer.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(path));
                });
            });

        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error configuring csrf token http security", e);
        }
    }
}
