package com.tosspaper.rbac;

import com.tosspaper.models.domain.PermissionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Spring Security PermissionEvaluator implementation for company-scoped RBAC with Redis caching.
 * <p>
 * Usage in controllers:
 * <pre>
 * {@code @PreAuthorize("hasPermission(#companyId, 'company', 'companies:edit')")}
 * public void updateCompany(@PathVariable Long companyId, ...) { ... }
 * </pre>
 *
 * How it works:
 * 1. Extracts user email from authentication (JWT contains email for identification)
 * 2. Fetches current role from Redis cache (or DB on cache miss)
 * 3. Checks role against static PermissionRegistry
 * <p>
 * Benefits over JWT-only approach:
 * - Immediate role revocation via cache eviction
 * - Max 5-minute staleness window (vs 60 minutes with JWT-only)
 * - Minimal latency impact (~1-5ms per request)
 * <p>
 * NOTE: JWT authorities claim is NOT used. All authorization decisions
 * are based on real-time database data via Redis cache.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanyPermissionEvaluator implements PermissionEvaluator {

    private final UserRoleCacheService userRoleCacheService;

    /**
     * Check if authenticated user has permission for a specific company.
     *
     * @param authentication Spring Security authentication (contains user info)
     * @param targetDomainObject Company ID (Long)
     * @param permission Permission to check (format: "resource:action" e.g., "companies:edit")
     * @return true if user has permission
     */
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        if (targetDomainObject == null || permission == null) {
            return false;
        }

        // Extract company ID and permission string
        Long companyId = Long.parseLong(targetDomainObject.toString());
        String permissionStr = permission.toString();

        // Extract user email from authentication
        String email = extractEmail(authentication);
        if (email == null) {
            log.warn("Could not extract email from authentication for company {}", companyId);
            return false;
        }

        // Get current role from cache or DB (via UserRoleCacheService)
        String roleId = userRoleCacheService.getUserRole(email, companyId);
        if (roleId == null) {
            log.debug("User {} has no role for company {}", email, companyId);
            return false;
        }

        // Check permission against PermissionRegistry
        boolean hasPermission = PermissionRegistry.hasPermission(roleId, permissionStr);

        log.debug("Permission check: user={}, company={}, role={}, permission={}, result={}",
                email, companyId, roleId, permissionStr, hasPermission);

        return hasPermission;
    }

    /**
     * Check if authenticated user has permission for a target (with targetId and targetType).
     * This overload is used when you need more fine-grained control.
     *
     * @param authentication Spring Security authentication
     * @param targetId Target identifier (e.g., companyId)
     * @param targetType Type of target (e.g., "company", "project")
     * @param permission Permission to check
     * @return true if user has permission
     */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (targetId == null) {
            return false;
        }

        // For company-scoped permissions, delegate to main method
        if ("company".equals(targetType)) {
            return hasPermission(authentication, targetId, permission);
        }

        log.warn("Unsupported targetType: {}", targetType);
        return false;
    }

    /**
     * Extract email from authentication object.
     * Supports both OAuth2 (Supabase) and standard authentication.
     *
     * @param authentication Spring Security authentication
     * @return User's email address or null if not found
     */
    private String extractEmail(Authentication authentication) {
        // Try OAuth2User (Supabase JWT)
        if (authentication.getPrincipal() instanceof DefaultOAuth2User user) {
            String email = user.getAttribute("email");
            if (email != null) {
                return email;
            }
        }

        // Fallback to getName() which should be email for Supabase
        String name = authentication.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }

        return null;
    }
}
