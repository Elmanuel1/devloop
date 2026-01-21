package com.tosspaper.httpsecurity;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.properties.JwtClaimProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

@RequiredArgsConstructor
public class EmailVerificationJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final JwtClaimProperties jwtClaimProperties;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!jwtClaimProperties.getEmailVerification().isEnabled()) {
            return OAuth2TokenValidatorResult.success();
        }

        String claimName = jwtClaimProperties.getEmailVerified();
        var isVerified = Optional.ofNullable(token.getClaimAsBoolean(claimName)).orElse(false);

        if (isVerified) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("email_verification_required", ApiErrorMessages.EMAIL_VERIFICATION_REQUIRED, null)
        );
    }
} 