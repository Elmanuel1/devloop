package com.tosspaper.common.security;

import com.tosspaper.common.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class
    }

    public static String getSubjectFromJwt() {
        // Try to get email first (for Supabase), fallback to sub
        String email = Optional.ofNullable(getClaimFromJwt("email"))
                .map(Object::toString)
                .orElse(null);
        
        if (email != null) {
            return email;
        }
        
        // Fallback to sub claim
        return Optional.ofNullable(getClaimFromJwt("sub"))
                .map(Object::toString)
                .orElseThrow(UnauthorizedException::new);
    }

    public static Object getClaimFromJwt(String claim) {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(authentication -> authentication.getPrincipal() instanceof DefaultOAuth2User)
                .map(authentication -> (DefaultOAuth2User) authentication.getPrincipal())
                .map(jwt -> jwt.getAttributes().get(claim))
                .orElse(null);
    }
} 