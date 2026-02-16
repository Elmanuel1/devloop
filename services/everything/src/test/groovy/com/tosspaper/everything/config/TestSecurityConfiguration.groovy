package com.tosspaper.everything.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.security.access.PermissionEvaluator
import org.springframework.security.core.Authentication

import java.io.Serializable

@TestConfiguration
class TestSecurityConfiguration {
    static final Long TEST_COMPANY_ID = 1L
    static final String TEST_USER_EMAIL = "aribooluwatoba@gmail.com"

    @Bean
    @Primary
    PermissionEvaluator permissionEvaluator() {
        return new PermissionEvaluator() {
            @Override
            boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
                if (authentication == null || !authentication.isAuthenticated()) {
                    return false
                }
                if (targetDomainObject == null) {
                    return false
                }
                try {
                    Long.parseLong(targetDomainObject.toString())
                } catch (Exception ignored) {
                    return false
                }
                return authentication.getName() == TEST_USER_EMAIL
            }

            @Override
            boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
                if (targetId == null) {
                    return false
                }
                return hasPermission(authentication, targetId, permission)
            }
        }
    }

    static String getTestToken() {
        return new ClassPathResource("token.jwt").getInputStream().text.trim()
    }
}
