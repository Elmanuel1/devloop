package com.tosspaper.integrations.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderItem;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.IntegrationAccountService;
import com.tosspaper.models.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enriches PO line items with QuickBooks metadata (itemRef/accountRef) from Items and Accounts tables.
 * Used before pushing to QuickBooks to ensure all line items have the required DetailType and references.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderLineItemEnricher {

    private final ItemService itemService;
    private final IntegrationAccountService integrationAccountService;
    private final ContactSyncService contactSyncService;

    /**
     * Enrich line items with QuickBooks metadata by batch-fetching Items and Accounts.
     * Updates each line item's metadata with itemRef or accountRef for QuickBooks mapping.
     *
     * @param connectionId the integration connection ID
     * @param purchaseOrders the list of purchase orders to enrich
     */
    public void enrichLineItems(String connectionId, List<PurchaseOrder> purchaseOrders) {
        if (purchaseOrders == null || purchaseOrders.isEmpty()) {
            return;
        }

        // Collect all itemIds, accountIds, vendorIds, and shipToIds from all POs
        Set<String> itemIds = new HashSet<>();
        Set<String> accountIds = new HashSet<>();
        Set<String> vendorIds = new HashSet<>();
        Set<String> shipToIds = new HashSet<>();
        for (PurchaseOrder po : purchaseOrders) {
            if (po.getItems() == null) continue;

            // Collect vendor ID to refresh external ID
            if (po.getVendorContact() != null && po.getVendorContact().getId() != null) {
                vendorIds.add(po.getVendorContact().getId());
            }

            // Collect ship-to ID to refresh external ID
            if (po.getShipToContact() != null && po.getShipToContact().getId() != null) {
                shipToIds.add(po.getShipToContact().getId());
            }

            for (PurchaseOrderItem lineItem : po.getItems()) {
                if (lineItem.getItemId() != null) {
                    itemIds.add(lineItem.getItemId());
                }
                if (lineItem.getAccountId() != null) {
                    accountIds.add(lineItem.getAccountId());
                }
            }
        }



        // Batch fetch vendors, ship-tos, items, and accounts
        Map<String, com.tosspaper.models.domain.Party> vendorMap = vendorIds.isEmpty()
                ? Map.of()
                : contactSyncService.findByIds(List.copyOf(vendorIds)).stream()
                        .collect(Collectors.toMap(com.tosspaper.models.domain.Party::getId, vendor -> vendor));

        Map<String, com.tosspaper.models.domain.Party> shipToMap = shipToIds.isEmpty()
                ? Map.of()
                : contactSyncService.findByIds(List.copyOf(shipToIds)).stream()
                        .collect(Collectors.toMap(com.tosspaper.models.domain.Party::getId, shipTo -> shipTo));

        Map<String, Item> itemMap = itemIds.isEmpty()
                ? Map.of()
                : itemService.findByIds(List.copyOf(itemIds)).stream()
                        .collect(Collectors.toMap(Item::getId, item -> item));

        Map<String, IntegrationAccount> accountMap = accountIds.isEmpty()
                ? Map.of()
                : integrationAccountService.findByIds(connectionId, List.copyOf(accountIds)).stream()
                        .collect(Collectors.toMap(IntegrationAccount::getId, account -> account));

        // Enrich vendors, ship-tos, and line items with external IDs (provider-agnostic)
        int vendorsEnriched = 0;
        int shipTosEnriched = 0;
        int itemsEnriched = 0;
        int accountsEnriched = 0;

        for (PurchaseOrder po : purchaseOrders) {
            // Enrich vendor contact with fresh external ID from database
            if (po.getVendorContact() != null && po.getVendorContact().getId() != null) {
                com.tosspaper.models.domain.Party vendor = vendorMap.get(po.getVendorContact().getId());
                if (vendor != null && vendor.getExternalId() != null) {
                    po.getVendorContact().setExternalId(vendor.getExternalId());
                    po.getVendorContact().setProviderVersion(vendor.getProviderVersion());
                    vendorsEnriched++;
                    log.debug("Set externalId={} for vendor {} in PO={}",
                            vendor.getExternalId(), vendor.getId(), po.getDisplayId());
                }
            }

            // Enrich ship-to contact with fresh external ID from database
            if (po.getShipToContact() != null && po.getShipToContact().getId() != null) {
                com.tosspaper.models.domain.Party shipTo = shipToMap.get(po.getShipToContact().getId());
                if (shipTo != null && shipTo.getExternalId() != null) {
                    po.getShipToContact().setExternalId(shipTo.getExternalId());
                    po.getShipToContact().setProviderVersion(shipTo.getProviderVersion());
                    shipTosEnriched++;
                    log.debug("Set externalId={} for ship-to {} in PO={}",
                            shipTo.getExternalId(), shipTo.getId(), po.getDisplayId());
                }
            }

            if (po.getItems() == null) continue;
            for (PurchaseOrderItem lineItem : po.getItems()) {

                // Set externalItemId if itemId is present
                if (lineItem.getItemId() != null) {
                    Item item = itemMap.get(lineItem.getItemId());
                    if (item != null && item.getExternalId() != null) {
                        lineItem.setExternalItemId(item.getExternalId());
                        itemsEnriched++;
                        log.debug("Set externalItemId={} for line item with itemId={} in PO={}",
                                item.getExternalId(), lineItem.getItemId(), po.getDisplayId());
                    }
                }

                // Set externalAccountId if accountId is present
                if (lineItem.getAccountId() != null) {
                    IntegrationAccount account = accountMap.get(lineItem.getAccountId());
                    if (account != null && account.getExternalId() != null) {
                        lineItem.setExternalAccountId(account.getExternalId());
                        accountsEnriched++;
                        log.debug("Set externalAccountId={} for line item with accountId={} in PO={}",
                                account.getExternalId(), lineItem.getAccountId(), po.getDisplayId());
                    }
                }
            }
        }

        log.debug("Enriched {} vendors, {} ship-tos, {} line items with externalItemId, {} with externalAccountId for {} POs",
                vendorsEnriched, shipTosEnriched, itemsEnriched, accountsEnriched, purchaseOrders.size());
    }
}
