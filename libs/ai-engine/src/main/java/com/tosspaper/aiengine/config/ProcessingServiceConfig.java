package com.tosspaper.aiengine.config;

import com.tosspaper.aiengine.service.ProcessingService;
import com.tosspaper.aiengine.service.impl.ReductoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for ProcessingService bean selection.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProcessingServiceConfig {

    private final ReductoProcessingService reductoProcessingService;

    /**
     * Provides the active ProcessingService.
     *
     * @return the configured ProcessingService implementation
     */
    @Bean
    public ProcessingService processingService() {
        log.info("Configuring ProcessingService with Reducto provider");
        return reductoProcessingService;
    }
}
