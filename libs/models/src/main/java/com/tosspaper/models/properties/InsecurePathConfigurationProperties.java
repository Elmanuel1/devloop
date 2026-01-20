package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "http.security.insecure")
public class InsecurePathConfigurationProperties {
    private List<String> paths = new ArrayList<>();
}
