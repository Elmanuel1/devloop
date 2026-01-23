package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderItem;
import com.tosspaper.models.service.IntegrationAccountService;
import com.tosspaper.models.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves external item/account references to internal IDs for PO line items.
 * Used during pull sync and webhook processing to map provider's external IDs
 * (e.g., QuickBooks ItemRef.value) to internal items.id and integration_accounts.id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderLineItemResolver {

    private final ItemService itemService;
    private final IntegrationAccountService integrationAccountService;

    /**
     * Resolve itemIds and accountIds for all PO line items by looking up by their external IDs.
     * Uses externalItemId and externalAccountId fields set during mapping.
     *
     * @param connectionId the integration connection ID
     * @param purchaseOrders the list of purchase orders to resolve
     */
    public void resolveLineItemReferences(String connectionId, List<PurchaseOrder> purchaseOrders) {
        // Collect all external IDs from all PO line items
        Set<String> externalItemIds = new HashSet<>();
        Set<String> externalAccountIds = new HashSet<>();
        
        for (PurchaseOrder po : purchaseOrders) {
            if (po.getItems() == null) continue;
            for (PurchaseOrderItem item : po.getItems()) {
                if (item.getExternalItemId() != null) {
                    externalItemIds.add(item.getExternalItemId());
                }
                if (item.getExternalAccountId() != null) {
                    externalAccountIds.add(item.getExternalAccountId());
                }
            }
        }
        
        // Batch lookup items: external_id -> internal item.id
        Map<String, String> itemMap = externalItemIds.isEmpty() 
            ? Map.of() 
            : itemService.findIdsByExternalIdsAndConnection(connectionId, List.copyOf(externalItemIds));
        
        // Batch lookup accounts: external_id -> internal account.id
        Map<String, String> accountMap = externalAccountIds.isEmpty()
            ? Map.of()
            : integrationAccountService.findIdsByExternalIdsAndConnection(connectionId, List.copyOf(externalAccountIds));
        
        // Assign resolved IDs to PO line items
        int itemsResolved = 0;
        int itemsMissing = 0;
        int accountsResolved = 0;
        int accountsMissing = 0;
        
        for (PurchaseOrder po : purchaseOrders) {
            if (po.getItems() == null) continue;
            for (PurchaseOrderItem item : po.getItems()) {
                // Resolve itemId
                String externalItemId = item.getExternalItemId();
                if (externalItemId != null) {
                    if (itemMap.containsKey(externalItemId)) {
                        item.setItemId(itemMap.get(externalItemId));
                        itemsResolved++;
                    } else {
                        log.debug("Item not found for externalId: {} in PO: {}", externalItemId, po.getDisplayId());
                        itemsMissing++;
                    }
                }
                
                // Resolve accountId
                String externalAccountId = item.getExternalAccountId();
                if (externalAccountId != null) {
                    if (accountMap.containsKey(externalAccountId)) {
                        item.setAccountId(accountMap.get(externalAccountId));
                        accountsResolved++;
                    } else {
                        log.debug("Account not found for externalId: {} in PO: {}", externalAccountId, po.getDisplayId());
                        accountsMissing++;
                    }
                }
            }
        }
        
        log.debug("Resolved {} items ({} missing), {} accounts ({} missing) for {} POs", 
            itemsResolved, itemsMissing, accountsResolved, accountsMissing, purchaseOrders.size());
    }
}

