package com.tosspaper.httpsecurity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

public final class GlobalAccessDeniedHandler implements AccessDeniedHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        var code = "unauthorized";
        var status = HttpServletResponse.SC_UNAUTHORIZED;
        if (accessDeniedException instanceof CsrfException) {
            code = "csrf_token_invalid";
            status = HttpServletResponse.SC_FORBIDDEN;
        } else if (request.getUserPrincipal() instanceof AbstractOAuth2TokenAuthenticationToken) { // Noncompliant
            code = "insufficient_scope";
            status = HttpServletResponse.SC_FORBIDDEN;
        }
        var error = new ApiError(
                code,
                "Access denied: " + accessDeniedException.getMessage());
        response.getWriter().write(MAPPER.writeValueAsString(error));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    }
}
