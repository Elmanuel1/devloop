package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "http.security.cors")
public class AllowedCorsDomainsConfigurationProperties {
    private List<String> domains = new ArrayList<>();
}
