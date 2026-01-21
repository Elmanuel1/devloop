package com.tosspaper.httpsecurity.config;

import com.tosspaper.models.properties.CsrfCookieProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

@Configuration
public class CsrfConfiguration {

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(
            CookieCsrfTokenRepository cookieCsrfTokenRepository) {
        return new CsrfAuthenticationStrategy(cookieCsrfTokenRepository);
    }

    @Bean
    public CookieCsrfTokenRepository cookieCsrfTokenRepository(CsrfCookieProperties csrfCookieProperties) {
        var repository = new CookieCsrfTokenRepository();
        repository.setCookieName(csrfCookieProperties.getCookieName());
        repository.setHeaderName(csrfCookieProperties.getCookieName());
        repository.setParameterName(csrfCookieProperties.getCookieName());

        repository.setCookieCustomizer(responseCookieBuilder -> {
            responseCookieBuilder.httpOnly(csrfCookieProperties.isHttpOnly());
            responseCookieBuilder.secure(csrfCookieProperties.isSecure());
            responseCookieBuilder.sameSite(csrfCookieProperties.getSameSite().toString());
            responseCookieBuilder.maxAge(csrfCookieProperties.getMaxAge());
            responseCookieBuilder.domain(csrfCookieProperties.getDomain());
            responseCookieBuilder.path(csrfCookieProperties.getPath());
        });

        return repository;
    }
}
