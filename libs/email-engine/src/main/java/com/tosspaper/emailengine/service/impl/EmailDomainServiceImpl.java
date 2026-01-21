package com.tosspaper.emailengine.service.impl;

import com.tosspaper.models.service.EmailDomainService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EmailDomainServiceImpl implements EmailDomainService {

    private static final Set<String> PERSONAL_DOMAINS = Set.of(
        "gmail.com",
        "yahoo.com",
        "outlook.com",
        "hotmail.com",
        "live.com",
        "icloud.com",
        "aol.com",
        "mail.com",
        "protonmail.com",
        "yandex.com"
    );

    private final Set<String> disposableDomains = ConcurrentHashMap.newKeySet();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.disposable-email.blocklist-file}")
    private String blocklistFile;

    @Value("${app.disposable-email.fetch-url}")
    private String fetchUrl;

    @PostConstruct
    public void loadBlocklistFromFile() {
        try {
            Path path = Paths.get(blocklistFile);
            if (!Files.exists(path)) {
                log.warn("Disposable email blocklist file not found: {}", blocklistFile);
                return;
            }

            Set<String> domains = ConcurrentHashMap.newKeySet();
            Files.readAllLines(path).stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(domains::add);

            disposableDomains.clear();
            disposableDomains.addAll(domains);
            log.info("Loaded {} disposable email domains from file", disposableDomains.size());
        } catch (IOException e) {
            log.error("Failed to load disposable email blocklist from file: {}", blocklistFile, e);
        }
    }

    @Scheduled(fixedRateString = "#{${app.disposable-email.fetch-interval-hours} * 3600000}", 
               initialDelayString = "#{${app.disposable-email.fetch-interval-hours} * 3600000}")
    @ConditionalOnProperty(name = "app.disposable-email.fetch-enabled", havingValue = "true")
    public void updateBlocklistFromGitHub() {
        try {
            log.info("Fetching disposable email blocklist from GitHub: {}", fetchUrl);
            String content = restTemplate.getForObject(fetchUrl, String.class);
            
            if (content == null || content.isBlank()) {
                log.warn("Received empty content from GitHub blocklist URL");
                return;
            }

            Set<String> newDomains = ConcurrentHashMap.newKeySet();
            content.lines()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .forEach(newDomains::add);

            disposableDomains.clear();
            disposableDomains.addAll(newDomains);
            log.info("Updated disposable email blocklist with {} domains from GitHub", disposableDomains.size());

            // Update local file for persistence
            try {
                Path path = Paths.get(blocklistFile);
                Files.write(path, content.getBytes());
                log.info("Saved updated blocklist to file: {}", blocklistFile);
            } catch (IOException e) {
                log.warn("Failed to save updated blocklist to file: {}", blocklistFile, e);
            }
        } catch (Exception e) {
            log.error("Failed to fetch disposable email blocklist from GitHub", e);
        }
    }

    /**
     * Check if the given domain is a disposable email domain
     * @param domain The domain to check (e.g., "tempmail.com")
     * @return true if the domain is in the disposable domains blocklist
     */
    private boolean isDisposableDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        return disposableDomains.contains(domain.toLowerCase());
    }

    /**
     * Check if the given domain is a personal email domain
     * @param domain The domain to check (e.g., "gmail.com")
     * @return true if the domain is in the personal domains blocklist
     */
    private boolean isPersonalDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        return PERSONAL_DOMAINS.contains(domain.toLowerCase());
    }

    /**
     * Check if the given domain is blocked (either disposable or personal)
     * @param domain The domain to check
     * @return true if the domain is blocked
     */
    public boolean isBlockedDomain(String domain) {
        return isDisposableDomain(domain) || isPersonalDomain(domain);
    }
}

