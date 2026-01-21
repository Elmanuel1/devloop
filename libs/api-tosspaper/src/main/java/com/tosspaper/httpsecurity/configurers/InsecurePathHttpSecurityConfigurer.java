package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class InsecurePathHttpSecurityConfigurer implements HttpSecurityConfigurer {

    private final List<String> paths;

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {

        paths.forEach(path -> {
            try {
                log.warn("permitting all requests to path {}", path);
                http.authorizeHttpRequests(
                        authorizationManagerRequestMatcherRegistry -> authorizationManagerRequestMatcherRegistry
                                .requestMatchers(new AntPathRequestMatcher(path))
                                .permitAll());
            } catch (Exception e) {
                throw new HttpSecurityConfigurationException("Cannot grant permitAll access to configured paths", e);
            }
        });
    }
}
