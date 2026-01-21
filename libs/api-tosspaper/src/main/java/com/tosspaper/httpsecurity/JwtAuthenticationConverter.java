package com.tosspaper.httpsecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts Supabase JWT tokens into Spring Security authentication objects.
 *
 * This converter extracts basic user information (email, sub) from JWT claims
 * for user identification purposes only.
 *
 * Authorization is handled at the service layer, not via JWT claims.
 */
@Slf4j
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // Create a map with the claims and ensure email is available
        Map<String, Object> attributes = new HashMap<>(jwt.getClaims());

        // Ensure email is available in the attributes
        if (jwt.getClaim("email") != null) {
            attributes.put("email", jwt.getClaim("email"));
        } else if (jwt.getClaim("sub") != null) {
            // If no explicit email claim, use sub as email (common in test tokens)
            attributes.put("email", jwt.getClaim("sub"));
        }

        // No authorities - authorization is handled at the service layer
        // JWT is only used for authentication (user identification)
        return new BearerTokenAuthentication(
                new DefaultOAuth2User(Collections.emptySet(), attributes, "sub"),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        jwt.getTokenValue(),
                        jwt.getIssuedAt(),
                        jwt.getExpiresAt()),
                Collections.emptySet());  // Empty authorities - not used for authorization
    }
}
