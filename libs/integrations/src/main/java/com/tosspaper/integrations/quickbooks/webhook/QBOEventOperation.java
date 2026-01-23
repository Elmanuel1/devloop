package com.tosspaper.integrations.quickbooks.webhook;

/**
 * Operations that can occur on QuickBooks entities via CloudEvents webhooks.
 */
public enum QBOEventOperation {
    CREATED,
    UPDATED,
    DELETED;

    /**
     * Parse operation from CloudEvents type string.
     * Example: "qbo.purchaseorder.deleted.v1" -> DELETED
     *
     * @param operation the operation string (created, updated, deleted)
     * @return the corresponding QBOEventOperation
     */
    public static QBOEventOperation fromString(String operation) {
        if (operation == null) {
            return null;
        }
        return valueOf(operation.toUpperCase());
    }
}
