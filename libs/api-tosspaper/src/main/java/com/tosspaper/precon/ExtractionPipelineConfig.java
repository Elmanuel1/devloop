package com.tosspaper.precon;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class ExtractionPipelineConfig {

    @Bean
    public Executor extractionProcessingExecutor(ExtractionProcessingProperties props) {
        return Executors.newFixedThreadPool(
                props.getThreadPoolSize(),
                Thread.ofVirtual().factory()
        );
    }

    @Bean
    public OkHttpClient reductoHttpClient(ReductoProperties props) {
        return new OkHttpClient.Builder()
                .connectTimeout(props.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(props.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(props.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }
}
