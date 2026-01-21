package com.tosspaper.rbac;

import com.tosspaper.models.domain.AuthorizedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Service for caching user roles with Redis.
 * Provides immediate role revocation via cache eviction while maintaining
 * fast authorization checks with 5-minute TTL cache.
 */
@Service
@Slf4j
public class UserRoleCacheService {
    private final AuthorizedUserRepository authorizedUserRepository;

    public UserRoleCacheService(AuthorizedUserRepository authorizedUserRepository) {
        this.authorizedUserRepository = authorizedUserRepository;
    }

    /**
     * Get user's role for a specific company (with caching).
     * Cache key format: user:role::{email}::{companyId}
     * TTL: 5 minutes (configured in CacheConfig)
     *
     * @param email User's email address
     * @param companyId Company ID
     * @return Role ID (owner/admin/operations/viewer) or null if user not authorized
     */
    @Cacheable(value = "user-roles", key = "#email + '::' + #companyId", unless = "#result == null")
    public String getUserRole(String email, Long companyId) {
        log.debug("Cache miss - fetching role from DB for user {} in company {}", email, companyId);

        return authorizedUserRepository.findByCompanyIdAndEmail(companyId, email)
            .filter(AuthorizedUser::isEnabled)
            .map(AuthorizedUser::roleId)
            .orElse(null);
    }

    /**
     * Evict cached role when user's role changes or user is removed.
     * Provides immediate revocation of permissions.
     *
     * @param email User's email address
     * @param companyId Company ID
     */
    @CacheEvict(value = "user-roles", key = "#email + '::' + #companyId")
    public void evictUserRole(String email, Long companyId) {
        log.info("Evicted role cache for user {} in company {}", email, companyId);
    }

    /**
     * Evict all cached roles for a user across all companies.
     * Useful for global user changes (e.g., account deletion).
     *
     * @param email User's email address
     */
    @CacheEvict(value = "user-roles", allEntries = true, condition = "#email != null")
    public void evictAllUserRoles(String email) {
        log.info("Evicted all role caches for user {}", email);
    }
}
