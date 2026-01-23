package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Service for ensuring purchase orders have external IDs before being used in
 * other entities.
 * Auto-pushes purchase orders to provider if they lack external IDs.
 */
public interface PurchaseOrderDependencyPushService {

    /**
     * Ensures all purchase orders have external IDs by auto-pushing to provider if
     * needed.
     * Purchase orders with existing external IDs are skipped.
     *
     * @param connection     the integration connection
     * @param purchaseOrders list of purchase orders to check and push if needed
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureHaveExternalIds(
            IntegrationConnection connection,
            List<PurchaseOrder> purchaseOrders);
}
