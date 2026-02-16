package com.tosspaper.config

import com.tosspaper.models.config.MailgunProperties
import com.tosspaper.models.config.FrontendUrlProperties
import com.tosspaper.models.config.AppEmailProperties
import com.tosspaper.integrations.config.IntegrationProperties
import com.tosspaper.integrations.config.IntegrationEncryptionProperties
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.aiengine.properties.HttpProperties
import com.tosspaper.aiengine.properties.AIProperties
import com.tosspaper.models.properties.FileProperties
import com.tosspaper.models.config.SupabaseProperties
import com.tosspaper.models.properties.AllowedCorsDomainsConfigurationProperties
import com.tosspaper.models.properties.InsecurePathConfigurationProperties
import com.tosspaper.models.properties.IgnoredCsrfPathConfigurationProperties
import com.tosspaper.models.properties.AuthenticatedAccessConfigProperties
import com.tosspaper.models.properties.CsrfCookieProperties
import com.tosspaper.models.properties.JWTTokenProperties
import com.tosspaper.models.properties.JwkCacheProperties
import com.tosspaper.models.properties.JwtClaimProperties
import com.tosspaper.models.properties.AwsProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.security.access.PermissionEvaluator
import org.springframework.security.core.Authentication

import java.io.Serializable

@TestConfiguration
@EnableConfigurationProperties(AwsProperties.class)
class TestSecurityConfiguration {
    static final Long TEST_COMPANY_ID = 1L
    static final String TEST_USER_EMAIL = "aribooluwatoba@gmail.com"
    
    @Bean
    MailgunProperties mailgunProperties() {
        MailgunProperties props = new MailgunProperties()
        props.setApiKey("dummy-key")
        props.setDomain("dummy-domain")
        props.setFromEmail("test@test.com")
        return props
    }

    @Bean
    HttpProperties httpProperties() {
        return new HttpProperties()
    }

    @Bean
    AIProperties aiProperties() {
        AIProperties props = new AIProperties()
        props.setProvider("reducto")
        props.setApiKey("dummy")
        return props
    }

    @Bean
    FileProperties fileProperties() {
        return new FileProperties()
    }

    @Bean
    SupabaseProperties supabaseProperties() {
        SupabaseProperties props = new SupabaseProperties()
        props.setUrl("https://dummy.supabase.co")
        props.setServiceRoleKey("dummy-key")
        return props
    }

    @Bean
    FrontendUrlProperties frontendUrlProperties() {
        FrontendUrlProperties props = new FrontendUrlProperties()
        props.setBaseUrl("http://localhost:3000")
        return props
    }

    @Bean
    AppEmailProperties appEmailProperties() {
        AppEmailProperties props = new AppEmailProperties()
        props.setAllowedDomain("test.com")
        return props
    }

    @Bean
    AllowedCorsDomainsConfigurationProperties allowedCorsDomainsConfigurationProperties() {
        AllowedCorsDomainsConfigurationProperties props = new AllowedCorsDomainsConfigurationProperties()
        props.setDomains(["http://localhost:3000"])
        return props
    }

    @Bean
    InsecurePathConfigurationProperties insecurePathConfigurationProperties() {
        InsecurePathConfigurationProperties props = new InsecurePathConfigurationProperties()
        props.setPaths(["/api/v1/health", "/api/v1/csrf"])
        return props
    }

    @Bean
    IgnoredCsrfPathConfigurationProperties ignoredCsrfPathConfigurationProperties() {
        IgnoredCsrfPathConfigurationProperties props = new IgnoredCsrfPathConfigurationProperties()
        props.setPaths(["/api/v1/webhooks/**", "/v1/quickbooks/**"])
        return props
    }

    @Bean
    AuthenticatedAccessConfigProperties authenticatedAccessConfigProperties() {
        AuthenticatedAccessConfigProperties props = new AuthenticatedAccessConfigProperties()
        props.setPaths(["/api/v1/**"])
        return props
    }

    @Bean
    CsrfCookieProperties csrfCookieProperties() {
        return new CsrfCookieProperties()
    }

    @Bean
    JWTTokenProperties jwtTokenProperties() {
        JWTTokenProperties props = new JWTTokenProperties()
        props.setIssuer("https://test.supabase.co/auth/v1")
        props.setAudience("authenticated")
        props.setJwkSetUri("https://test.supabase.co/auth/v1/.well-known/jwks.json")
        return props
    }

    @Bean
    JwkCacheProperties jwkCacheProperties() {
        return new JwkCacheProperties()
    }

    @Bean
    JwtClaimProperties jwtClaimProperties() {
        return new JwtClaimProperties()
    }

    /**
     * Test-only PermissionEvaluator that allows all permissions for the test user
     * within the test company. This avoids needing RBAC seed data in every integration test.
     */
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

                Long companyId
                try {
                    companyId = Long.parseLong(targetDomainObject.toString())
                } catch (Exception ignored) {
                    return false
                }

                // Allow test user access to any company ID (tests create companies with auto-generated IDs)
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

    /**
     * Utility method to read the test JWT token from the file
     * This token is pre-generated with proper RSA signature
     */
    static String getTestToken() {
        return new ClassPathResource("token.jwt").getInputStream().text.trim()
    }
}