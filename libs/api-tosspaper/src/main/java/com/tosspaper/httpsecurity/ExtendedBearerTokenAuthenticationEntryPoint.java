package com.tosspaper.httpsecurity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public final class ExtendedBearerTokenAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        log.error("Unauthorized access to {}", request.getRequestURI(), authException.getCause());

        if (authException.getCause() instanceof JwtValidationException exception) {
            Optional<OAuth2Error> error = exception.getErrors()
                    .stream()
                    .filter(e -> "email_verification_required".equals(e.getErrorCode()))
                    .findFirst();

            if (error.isPresent()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                response.getWriter().write("""
                    {
                      "error": "email_verification_required",
                      "message": "Please verify your email before logging in."
                    }
                    """);
                return;
            }
        }

        // Default unauthorized response
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
        response.getWriter().write("""
            {
              "error": "unauthorized",
              "message": "Unauthorized access."
            }
            """);
    }
}
