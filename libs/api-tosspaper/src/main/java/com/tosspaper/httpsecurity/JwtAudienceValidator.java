package com.tosspaper.httpsecurity;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.function.Predicate;

public final class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final JwtClaimValidator<Object> validator;

    public JwtAudienceValidator(String audience) {
        Assert.notNull(audience, "audience cannot be null");
        Predicate<Object> testClaimValue = claimValue -> {
            if (claimValue == null) {
                return false;
            }
            if (claimValue instanceof String stringValue) {
                return audience.equals(stringValue);
            }
            if (claimValue instanceof Collection<?> collectionValue) {
                return collectionValue.stream()
                        .anyMatch(element -> element != null && audience.equals(element.toString()));
            }
            return false;
        };
        this.validator = new JwtClaimValidator<>("aud", testClaimValue);
    }

    public OAuth2TokenValidatorResult validate(Jwt token) {
        Assert.notNull(token, "token cannot be null");
        return this.validator.validate(token);
    }
}
