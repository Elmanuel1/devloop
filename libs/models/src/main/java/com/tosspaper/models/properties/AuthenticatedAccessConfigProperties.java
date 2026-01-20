package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "http.security.authenticated")
public class AuthenticatedAccessConfigProperties {
    private List<String> paths = new ArrayList<>();
}
