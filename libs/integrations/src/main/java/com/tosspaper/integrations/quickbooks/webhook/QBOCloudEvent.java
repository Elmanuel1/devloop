package com.tosspaper.integrations.quickbooks.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Represents a QuickBooks CloudEvent webhook notification.
 *
 * CloudEvents format:
 * {
 *   "specversion": "1.0",
 *   "id": "cda2f6f2-1a98-48b8-858d-b71a31370062-PurchaseOrder-45",
 *   "source": "intuit.gNrxXlevKjGOz7qbg3RqahZaNpT9qtJeVrHwh4rI4vY=",
 *   "type": "qbo.purchaseorder.updated.v1",
 *   "time": "2025-12-16T16:03:24Z",
 *   "intuitentityid": "45",
 *   "intuitaccountid": "9341455841580195"
 * }
 *
 * Type pattern: qbo.{entitytype}.{operation}.v1
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QBOCloudEvent {

    private String id;

    private String specversion;

    private String source;

    /**
     * CloudEvents type field.
     * Pattern: qbo.{entitytype}.{operation}.v1
     * Example: "qbo.purchaseorder.deleted.v1"
     */
    private String type;

    /**
     * Event timestamp in UTC.
     */
    private OffsetDateTime time;

    /**
     * QuickBooks entity ID.
     */
    @JsonProperty("intuitentityid")
    private String entityId;

    /**
     * QuickBooks realm/account ID.
     */
    @JsonProperty("intuitaccountid")
    private String accountId;

    /**
     * Optional data payload.
     */
    private Map<String, Object> data;

    // Entity type mapping from CloudEvents type to IntegrationEntityType
    private static final Map<String, IntegrationEntityType> ENTITY_TYPE_MAP = Map.of(
            "purchaseorder", IntegrationEntityType.PURCHASE_ORDER,
            "bill", IntegrationEntityType.BILL,
            "vendor", IntegrationEntityType.VENDOR,
            "customer", IntegrationEntityType.JOB_LOCATION,
            "account", IntegrationEntityType.ACCOUNT,
            "term", IntegrationEntityType.PAYMENT_TERM,
            "item", IntegrationEntityType.ITEM
    );

    /**
     * Extract entity type from the CloudEvents type field.
     * Example: "qbo.purchaseorder.deleted.v1" -> PURCHASE_ORDER
     *
     * @return the IntegrationEntityType, or null if not recognized
     */
    public IntegrationEntityType getEntityType() {
        if (type == null) {
            return null;
        }
        String[] parts = type.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        // parts[0] = "qbo", parts[1] = "purchaseorder", parts[2] = "deleted", parts[3] = "v1"
        String entityTypeName = parts[1].toLowerCase();
        return ENTITY_TYPE_MAP.get(entityTypeName);
    }

    /**
     * Extract operation from the CloudEvents type field.
     * Example: "qbo.purchaseorder.deleted.v1" -> DELETED
     *
     * @return the QBOEventOperation, or null if not recognized
     */
    public QBOEventOperation getOperation() {
        if (type == null) {
            return null;
        }
        String[] parts = type.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        // parts[0] = "qbo", parts[1] = "purchaseorder", parts[2] = "deleted", parts[3] = "v1"
        try {
            return QBOEventOperation.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
