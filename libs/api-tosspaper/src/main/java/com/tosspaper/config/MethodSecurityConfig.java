package com.tosspaper.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
    // Method security is enabled via @EnableMethodSecurity
    // Authorization is handled at the service layer
}
