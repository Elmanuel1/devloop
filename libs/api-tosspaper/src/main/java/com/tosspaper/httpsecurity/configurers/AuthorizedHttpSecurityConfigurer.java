package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import com.tosspaper.models.properties.AuthenticatedAccessConfigProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@RequiredArgsConstructor
@Slf4j
public class AuthorizedHttpSecurityConfigurer implements HttpSecurityConfigurer {

    private final AuthenticatedAccessConfigProperties authenticatedAccessConfig;

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            var paths = authenticatedAccessConfig.getPaths();
            if (paths.isEmpty()) {
                log.warn("No paths configured for authenticated access - permitting all requests");
                http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
                return;
            }

            log.debug("Configuring authenticated access for {} paths", paths.size());

            http.authorizeHttpRequests(authorize -> {
                for (String path : paths) {
                    authorize.requestMatchers(path).authenticated();
                }
                authorize.anyRequest().permitAll();
            });
        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error configuring authenticated access rules", e);
        }
    }
}
