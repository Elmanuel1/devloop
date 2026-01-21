package com.tosspaper.httpsecurity.configurers;

import com.tosspaper.httpsecurity.HttpSecurityConfigurationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class AllowedCorsDomainsHttpSecurityConfigurer implements HttpSecurityConfigurer {

    private final List<String> allowedOrigins;

    @Override
    public void configure(HttpSecurity httpSecurity) throws HttpSecurityConfigurationException {
        try {
            if (!allowedOrigins.isEmpty()) {
                log.warn("allowed cors for the following origin: {}", allowedOrigins);
                httpSecurity.cors(httpSecurityCorsConfigurer ->
                        httpSecurityCorsConfigurer.configurationSource(corsConfigurationSource()));
            }
        } catch (Exception e) {
            throw new HttpSecurityConfigurationException(e);
        }
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins); 
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of(
                "Location", "Content-Disposition", "Content-Type",
                "Content-Length", "ETag", "Last-Modified", "Access-Control-Request-Headers", 
                "X-Context-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
