package com.tosspaper.everything.healthchecks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service to track Redis Stream health status.
 * This service is not proxied and can be safely injected.
 */
@Slf4j
@Service
public class StreamHealthService {
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicInteger totalConsumers = new AtomicInteger(0);
    private final AtomicInteger registeredConsumers = new AtomicInteger(0);
    
    public boolean isInitialized() {
        return initialized.get();
    }
    
    public void setInitialized(boolean initialized) {
        this.initialized.set(initialized);
    }
    
    public int getTotalConsumers() {
        return totalConsumers.get();
    }
    
    public void setTotalConsumers(int totalConsumers) {
        this.totalConsumers.set(totalConsumers);
    }
    
    public int getRegisteredConsumers() {
        return registeredConsumers.get();
    }
    
    public void setRegisteredConsumers(int registeredConsumers) {
        this.registeredConsumers.set(registeredConsumers);
    }
    
    public void incrementRegisteredConsumers() {
        this.registeredConsumers.incrementAndGet();
    }
}
