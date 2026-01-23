package com.tosspaper.integrations.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.integrations.common.exception.IntegrationAuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Implementation of OAuth state service using Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthStateServiceImpl implements OAuthStateService {

    private static final String OAUTH_STATE_PREFIX = "oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void storeState(String state, Long companyId, String providerId) {
        String key = OAUTH_STATE_PREFIX + state;
        try {
            Map<String, String> stateData = Map.of(
                    "companyId", companyId.toString(),
                    "providerId", providerId
            );
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(stateData), STATE_TTL);
            log.debug("Stored OAuth state: state={}, companyId={}, providerId={}", state, companyId, providerId);
        } catch (Exception e) {
            log.error("Failed to store OAuth state", e);
            throw new IntegrationAuthException("Failed to store OAuth state", e);
        }
    }

    @Override
    public StateData validateAndConsumeState(String state) {
        String key = OAUTH_STATE_PREFIX + state;
        String stateDataJson = redisTemplate.opsForValue().getAndDelete(key);

        if (stateDataJson == null) {
            log.warn("Invalid or expired OAuth state: state={}", state);
            throw new IntegrationAuthException("Invalid or expired OAuth state", "not_found_or_expired");
        }

        try {
            Map<String, String> stateData = objectMapper.readValue(stateDataJson, new TypeReference<>() {});
            Long companyId = Long.parseLong(stateData.get("companyId"));
            String providerId = stateData.get("providerId");
            log.debug("Validated OAuth state: state={}, companyId={}, providerId={}", state, companyId, providerId);
            return new StateData(companyId, providerId);
        } catch (Exception e) {
            log.error("Invalid OAuth state format: state={}", state, e);
            throw new IntegrationAuthException("Invalid OAuth state format", "invalid_state_format", e);
        }
    }
}
