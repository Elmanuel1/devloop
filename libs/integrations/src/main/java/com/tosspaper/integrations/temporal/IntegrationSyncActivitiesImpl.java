package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Implementation of sync activities for the parent workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(workers = "integration-sync-worker")
public class IntegrationSyncActivitiesImpl implements IntegrationSyncActivities {

    private final IntegrationConnectionService connectionService;

    @Override
    public SyncConnectionData getConnection(String connectionId) {
        IntegrationConnection connection = connectionService.findById(connectionId);
        if (!connection.isEnabled()) {
            throw new IntegrationException("Integration connection is not enabled: " + connectionId);
        }

        IntegrationConnection activeConnection = connectionService.ensureActiveToken(connection);
        return SyncConnectionData.from(activeConnection);
    }
}

