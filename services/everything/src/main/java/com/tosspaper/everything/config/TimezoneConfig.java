package com.tosspaper.everything.config;

import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class TimezoneConfig {

    @jakarta.annotation.PostConstruct
    public void init() {
        // Set default JVM timezone to UTC
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
