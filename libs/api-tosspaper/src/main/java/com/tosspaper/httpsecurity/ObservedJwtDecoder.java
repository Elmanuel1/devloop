package com.tosspaper.httpsecurity;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Wraps a JwtDecoder with observability instrumentation to trace JWT validation operations.
 * This allows us to see how long JWT decoding and validation takes, including JWKS fetches from Supabase.
 */
@RequiredArgsConstructor
public class ObservedJwtDecoder implements JwtDecoder {
    
    private final JwtDecoder delegate;
    private final ObservationRegistry observationRegistry;
    
    @Override
    public Jwt decode(String token) throws JwtException {
        return Observation.createNotStarted("jwt.decode", observationRegistry)
            .contextualName("JWT validation")
            .lowCardinalityKeyValue("operation", "decode")
            .observe(() -> {
                try {
                    return delegate.decode(token);
                } catch (JwtException e) {
                    // Re-throw so the observation captures the error
                    throw e;
                }
            });
    }
}

