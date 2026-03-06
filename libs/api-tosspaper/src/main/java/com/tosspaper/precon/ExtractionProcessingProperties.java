package com.tosspaper.precon;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@ConfigurationProperties(prefix = "extraction.processing")
@Validated
public class ExtractionProcessingProperties {

    @Positive
    private int threadPoolSize = 5;

    @Positive
    private int batchSize = 20;

    @Positive
    private int staleMinutes = 15;
}
