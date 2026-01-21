package com.tosspaper.emailengine.service;

import com.tosspaper.models.service.SyncConflictNotificationRequest;
import org.springframework.stereotype.Component;

/**
 * Builder for sync conflict notification email content.
 * Used when pushing entities to external systems fails due to conflicts.
 */
@Component
public class SyncConflictEmailContentBuilder {

    /**
     * Build email subject for sync conflict notification.
     */
    public String buildSubject(String companyName, String provider, String entityType, String entityName) {
        return String.format("Sync Conflict: %s '%s' in %s", entityType, entityName, provider);
    }

    /**
     * Build email body for sync conflict notification.
     *
     * @param companyName the company name
     * @param request the sync conflict notification request
     * @return email body text
     */
    public String buildBody(String companyName, SyncConflictNotificationRequest request) {
        StringBuilder body = new StringBuilder();
        
        body.append("Hello,\n\n");
        body.append(String.format("A sync conflict was detected for %s '%s' in %s.\n\n", 
                request.entityType(), request.entityName(), request.provider()));
        
        body.append("What happened:\n");
        body.append(String.format("- %s\n\n", request.errorMessage()));
        
        body.append("What we did:\n");
        body.append(String.format("- We pulled the latest data from %s to ensure your local copy is up to date\n", request.provider()));
        body.append(String.format("- The conflict occurred because the entity was modified in %s since the last sync\n\n", request.provider()));
        
        body.append("What you should do:\n");
        body.append("- Review the updated entity in your system\n");
        body.append("- If you have questions, please contact support\n\n");
        
        body.append("This is an automated notification. Please do not reply to this email.\n\n");
        body.append("Best regards,\n");
        body.append("TossPaper Team");
        
        return body.toString();
    }
}

