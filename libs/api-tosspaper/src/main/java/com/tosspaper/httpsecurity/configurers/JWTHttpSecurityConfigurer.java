package com.tosspaper.httpsecurity.configurers;

import org.springframework.cache.Cache;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.tosspaper.httpsecurity.*;
import com.tosspaper.models.properties.JWTTokenProperties;
import com.tosspaper.models.properties.JwtClaimProperties;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@RequiredArgsConstructor
public class JWTHttpSecurityConfigurer implements HttpSecurityConfigurer {
    private final JWTTokenProperties jwtTokenProperties;
    private final JwtClaimProperties jwtClaimProperties;
    private final RestTemplate restTemplate;
    private final ObservationRegistry observationRegistry;
    private final Cache jwkCache;

    @Override
    public void configure(HttpSecurity http) throws HttpSecurityConfigurationException {
        try {
            http.oauth2ResourceServer(
                    oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter()))
                            .jwt(jwt -> jwt.decoder(from(jwtTokenProperties.getJwkSetUri()))
                            )

                            .authenticationEntryPoint(new ExtendedBearerTokenAuthenticationEntryPoint())
                            .accessDeniedHandler(new GlobalAccessDeniedHandler()));

        } catch (Exception e) {
            throw new HttpSecurityConfigurationException("Error configuring authorization for all endpoints", e);
        }
    }

    @SneakyThrows
    public JwtDecoder from(String uri) {
        var location = new URI(uri);
        return switch (location.getScheme()) {
            case "file" -> fromFile(location.getPath());
            case "http", "https" -> fromIssuerLocation();
            default -> throw new IllegalArgumentException("Unsupported URI scheme: " + location.getScheme());
        };
    }

    public JwtDecoder fromIssuerLocation() {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwtTokenProperties.getJwkSetUri())
                .restOperations(restTemplate) 
                .jwsAlgorithms(c ->  c.addAll(Set.of(SignatureAlgorithm.RS256, SignatureAlgorithm.ES256)))
                .cache(jwkCache)
                .jwtProcessorCustomizer(processor ->
                        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                                Set.of(
                                        JOSEObjectType.JWT,
                                        new JOSEObjectType(jwtTokenProperties.getType())
                                ))))

                        
                .build();

        OAuth2TokenValidator<Jwt> jwtValidator = JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(jwtTokenProperties.getIssuer()),
                new EmailVerificationJwtValidator(jwtClaimProperties)
        );
        jwtDecoder.setJwtValidator(jwtValidator);
        
        // Wrap with observability to trace JWT validation operations
        return new ObservedJwtDecoder(jwtDecoder, observationRegistry);
    }

    public JwtDecoder fromFile(String jwksFilePath) {
        Assert.hasText(jwksFilePath, "JWKS file path cannot be empty");
        try {
            // Load the JWKS from a file
            JWKSet jwkSet = JWKSet.load(new File(jwksFilePath));
            // Create a JWKSource that will provide the keys for JWT validation
            JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(jwkSet);

            // Create a JWSKeySelector for RSA algorithms
            JWSKeySelector<SecurityContext> jwsKeySelector =
                    new JWSAlgorithmFamilyJWSKeySelector<>(JWSAlgorithm.Family.RSA, jwkSource);
            
            // Create JWE key selector for encrypted JWTs (RSA keys only)
            JWEKeySelector<SecurityContext> jweKeySelector = (jweHeader, context) -> {
                // Create a JWK selector based on the JWE header
                var jwkSelector = new com.nimbusds.jose.jwk.JWKSelector(
                        new com.nimbusds.jose.jwk.JWKMatcher.Builder()
                                .keyType(com.nimbusds.jose.jwk.KeyType.forAlgorithm(jweHeader.getAlgorithm()))
                                .keyID(jweHeader.getKeyID())
                                .build()
                );
                var jwks = jwkSource.get(jwkSelector, context);
                return jwks.stream()
                        .map(jwk -> {
                            try {
                                // Only RSA keys are supported
                                return jwk.toRSAKey().toPrivateKey();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to extract RSA private key from JWK. Only RSA keys are supported.", e);
                            }
                        })
                        .toList();
            };
            
            DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            jwtProcessor.setJWSKeySelector(jwsKeySelector);
            jwtProcessor.setJWEKeySelector(jweKeySelector);
            jwtProcessor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                    Set.of(
                            JOSEObjectType.JWT,
                            new JOSEObjectType(jwtTokenProperties.getType())
                    )));

            OAuth2TokenValidator<Jwt> jwtValidator = JwtValidators.createDefaultWithValidators(
                    new JwtTimestampValidator(Duration.ZERO),
                    new JwtAudienceValidator(jwtTokenProperties.getAudience()),
                    new JwtIssuerValidator(jwtTokenProperties.getIssuer()),
                    new EmailVerificationJwtValidator(jwtClaimProperties));

            var decoder = new NimbusJwtDecoder(jwtProcessor);
            decoder.setJwtValidator(jwtValidator);

            return decoder;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load JWKS from file: " + jwksFilePath, e);
        }
    }
}
