package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import io.grpc.Status;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Manages Temporal schedules for integration sync workflows.
 * Creates one schedule per connection - each connection syncs independently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegrationScheduleManager {

    private final WorkflowServiceStubs workflowServiceStubs;
    private final QuickBooksProperties quickBooksProperties;

    private static final String TASK_QUEUE = "integration-sync";

    private ScheduleClient getScheduleClient() {
        return ScheduleClient.newInstance(workflowServiceStubs);
    }

    /**
     * Create a Temporal schedule for a connection.
     * Each connection gets its own schedule to sync independently.
     *
     * @param connection the integration connection
     */
    public void createSchedule(IntegrationConnection connection) {
        String scheduleId = buildScheduleId(connection);
        int intervalSeconds = quickBooksProperties.getSync().getSyncIntervalSeconds();

        try {
            Schedule schedule = Schedule.newBuilder()
                    .setAction(
                            ScheduleActionStartWorkflow.newBuilder()
                                    .setWorkflowType(IntegrationSyncWorkflow.class)
                                    .setArguments(connection.getId())
                                    .setOptions(
                                            WorkflowOptions.newBuilder()
                                                    .setWorkflowId(buildWorkflowId(connection))
                                                    .setTaskQueue(TASK_QUEUE)
                                                    .setStaticSummary(buildWorkflowSummary(connection))
                                                    .setStaticDetails(buildWorkflowDetails(connection))
                                                    .build())
                                    .build())
                    .setSpec(ScheduleSpec.newBuilder()
                            .setIntervals(List.of(
                                    new ScheduleIntervalSpec(Duration.ofSeconds(intervalSeconds))
                            ))
                            .build())
                    .build();

            ScheduleClient scheduleClient = getScheduleClient();
            scheduleClient.createSchedule(
                    scheduleId,
                    schedule,
                    ScheduleOptions.newBuilder()
                            .setMemo(Map.of(
                                    "provider", connection.getProvider().name(),
                                    "companyName", connection.getExternalCompanyName() != null
                                            ? connection.getExternalCompanyName() : "Unknown",
                                    "connectionId", connection.getId()
                            ))
                            .build()
            );

            log.info("Created Temporal schedule for connection: id={}, scheduleId={}, interval={}s",
                    connection.getId(), scheduleId, intervalSeconds);
        } catch (ScheduleAlreadyRunningException e) {
            log.info("Schedule already exists for connection: {}", connection.getId());
        } catch (Exception e) {
            log.error("Failed to create schedule for connection: {}", connection.getId(), e);
            throw new RuntimeException("Failed to create sync schedule", e);
        }
    }

    /**
     * Pause a Temporal schedule for a connection.
     * Used when user disables sync but keeps the connection.
     *
     * @param connection the integration connection
     */
    public void pauseSchedule(IntegrationConnection connection) {
        String scheduleId = buildScheduleId(connection);
        try {
            ScheduleClient scheduleClient = getScheduleClient();
            ScheduleHandle handle = scheduleClient.getHandle(scheduleId);
            handle.pause("Sync disabled due to paused sync");
            log.info("Paused Temporal schedule for connection: id={}, scheduleId={}",
                    connection.getId(), scheduleId);
        } catch (Exception e) {
            log.warn("Failed to pause schedule for connection: {} (may not exist)",
                    connection.getId(), e);
        }
    }

    /**
     * Unpause a Temporal schedule for a connection.
     * If schedule doesn't exist (NOT_FOUND), creates a new one.
     * Other errors (UNAVAILABLE, DEADLINE_EXCEEDED, PERMISSION_DENIED) are propagated.
     *
     * @param connection the integration connection
     */
    public void unpauseSchedule(IntegrationConnection connection) {
        String scheduleId = buildScheduleId(connection);
        try {
            ScheduleClient scheduleClient = getScheduleClient();
            ScheduleHandle handle = scheduleClient.getHandle(scheduleId);
            handle.unpause("Sync enabled by user");
            log.info("Unpaused Temporal schedule for connection: id={}, scheduleId={}",
                    connection.getId(), scheduleId);
        } catch (Exception e) {
            Status grpcStatus = Status.fromThrowable(e);
            Status.Code code = grpcStatus.getCode();

            if (code == Status.Code.NOT_FOUND) {
                // Schedule doesn't exist, create it instead
                log.info("Schedule not found for connection: {}, creating new one", connection.getId());
                createSchedule(connection);
            } else if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                // Transient failure - log and rethrow
                log.error("Transient failure unpausing schedule for connection: {}, status: {}",
                        connection.getId(), code, e);
                throw new RuntimeException("Transient failure unpausing schedule: " + code, e);
            } else if (code == Status.Code.PERMISSION_DENIED || code == Status.Code.UNAUTHENTICATED) {
                // Auth failure - log and rethrow
                log.error("Authorization failure unpausing schedule for connection: {}, status: {}",
                        connection.getId(), code, e);
                throw new RuntimeException("Authorization failure unpausing schedule: " + code, e);
            } else {
                // Unknown error - log and rethrow
                log.error("Failed to unpause schedule for connection: {}, status: {}",
                        connection.getId(), code, e);
                throw new RuntimeException("Failed to unpause schedule: " + code, e);
            }
        }
    }

    /**
     * Delete a Temporal schedule for a connection.
     * Used when user disconnects the integration completely.
     *
     * @param connection the integration connection
     */
    public void deleteSchedule(IntegrationConnection connection) {
        String scheduleId = buildScheduleId(connection);
        try {
            ScheduleClient scheduleClient = getScheduleClient();
            ScheduleHandle handle = scheduleClient.getHandle(scheduleId);
            handle.delete();
            log.info("Deleted Temporal schedule for connection: id={}, scheduleId={}", connection.getId(), scheduleId);
        } catch (Exception e) {
            log.warn("Failed to delete schedule for connection: {} (may not exist)", connection.getId(), e);
        }
    }

    private String buildScheduleId(IntegrationConnection connection) {
        // Format: schedule-{provider}-{companyName}-{shortId}
        // Example: schedule-quickbooks-acme-corp-a1b2c3d4
        String provider = connection.getProvider().getValue();
        String companyName = sanitizeForId(connection.getExternalCompanyName());
        String shortId = connection.getId().substring(0, 8);

        return String.format("schedule-%s-%s-%s", provider, companyName, shortId);
    }

    private String buildWorkflowId(IntegrationConnection connection) {
        // Format: sync-{provider}-{companyName}-{shortId}
        // Example: sync-quickbooks-acme-corp-a1b2c3d4
        String provider = connection.getProvider().getValue();
        String companyName = sanitizeForId(connection.getExternalCompanyName());
        String shortId = connection.getId().substring(0, 8); // First 8 chars of UUID

        return String.format("sync-%s-%s-%s", provider, companyName, shortId);
    }

    private String sanitizeForId(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        // Lowercase, replace spaces/special chars with dashes, limit length
        String sanitized = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", ""); // Remove leading/trailing dashes
        return sanitized.substring(0, Math.min(sanitized.length(), 30)); // Limit to 30 chars
    }

    private String buildWorkflowSummary(IntegrationConnection connection) {
        String provider = connection.getProvider().getDisplayName();
        String companyName = connection.getExternalCompanyName() != null
                ? connection.getExternalCompanyName() : "Unknown";
        return String.format("%s sync for %s", provider, companyName);
    }

    private String buildWorkflowDetails(IntegrationConnection connection) {
        String provider = connection.getProvider().getDisplayName();
        String companyName = connection.getExternalCompanyName();
        String realmId = connection.getRealmId();
        return String.format("Syncing approved documents from Tosspaper to %s for %s (Realm: %s)",
                provider, companyName, realmId);
    }
}
