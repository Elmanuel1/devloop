package com.tosspaper.models.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@ConfigurationProperties(prefix = "http.security.csrf-ignored")
public class IgnoredCsrfPathConfigurationProperties {
    private List<String> paths = new ArrayList<>();
}
