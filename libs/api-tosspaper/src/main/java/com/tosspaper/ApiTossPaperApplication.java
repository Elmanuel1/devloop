package com.tosspaper;

import com.tosspaper.models.config.AppEmailProperties;
import com.tosspaper.precon.ExtractionProcessingProperties;
import com.tosspaper.precon.TenderFileProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties({AppEmailProperties.class, TenderFileProperties.class, ExtractionProcessingProperties.class})
@EnableScheduling
@EnableMethodSecurity
public class ApiTossPaperApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiTossPaperApplication.class, args);
    }

    @PostConstruct
    public void init() {
        // Setting Spring Boot SetTimeZone
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
} 