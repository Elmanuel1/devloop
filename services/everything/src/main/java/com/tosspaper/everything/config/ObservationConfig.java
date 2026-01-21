package com.tosspaper.everything.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Configuration for observability to exclude health check pings and routine polling operations from traces.
 * This allows database queries and Redis operations to be observed,
 * but filters out health check operations and routine stream polling that would clutter the traces.
 *
 * Also enables @Observed annotation support via AspectJ proxying for method-level span instrumentation.
 */
@Slf4j
@Configuration
@EnableAspectJAutoProxy
public class ObservationConfig {

    /**
     * ObservationPredicate to exclude health check operations and routine Redis stream polling from observation.
     * This filters out Redis ping, database ping operations from health checks, and XREADGROUP polling commands,
     * while still observing actual application queries and message processing.
     */
    @Bean
    public ObservationPredicate healthCheckObservationPredicate() {
        return new ObservationPredicate() {
            @Override
            public boolean test(String name, Observation.Context context) {
                // Exclude observations from health check operations
                if (name != null) {
                    String lowerName = name.toLowerCase();
                    // Exclude health check related observations
                    if (lowerName.contains("health") || 
                        lowerName.contains("actuator.health") ||
                        lowerName.contains("redis.health") ||
                        lowerName.contains("db.health") ||
                        lowerName.contains("datasource.health")) {
                        return false; // Don't observe health checks
                    }
                }
                
                // Also check context for health-related tags
                if (context != null) {
                    Object uriObj = context.get("uri");
                    if (uriObj instanceof String) {
                        String uri = (String) uriObj;
                        if (uri.contains("/actuator/health")) {
                            return false; // Don't observe health endpoint requests
                        }
                    }
                    
                    // Exclude routine Redis stream polling commands (XREADGROUP) that happen every 2 seconds
                    // These are normal polling operations and shouldn't trigger alerts
                    Object dbOperation = context.get("db.operation");
                    if (dbOperation instanceof String) {
                        String operation = (String) dbOperation;
                        if ("XREADGROUP".equals(operation)) {
                            return false; // Don't observe routine stream polling
                        }
                    }
                    
                    // Also check db.statement for XREADGROUP commands
                    Object dbStatement = context.get("db.statement");
                    if (dbStatement instanceof String) {
                        String statement = (String) dbStatement;
                        if (statement != null && statement.toUpperCase().startsWith("XREADGROUP")) {
                            return false; // Don't observe routine stream polling
                        }
                    }
                }
                
                return true; // Observe all other operations
            }
        };
    }

    /**
     * Bean to enable @Observed annotation support on service methods.
     * This allows declarative span creation via annotations.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}

