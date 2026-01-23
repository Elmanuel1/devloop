package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderItem;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.IntegrationAccountService;
import com.tosspaper.models.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dependency strategy for Purchase Orders.
 * Ensures all PO dependencies (ship-tos, vendors, items, accounts) have external IDs before pushing.
 * Dependency order: Ship-tos → Vendors → Accounts → Items (fail-fast).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderDependencyStrategy implements DependencyStrategy {

    private final CustomerDependencyPushService customerService;
    private final VendorDependencyPushService vendorService;
    private final ItemDependencyPushService itemService;
    private final AccountDependencyService accountService;
    private final ContactSyncService contactSyncService;
    private final ItemService itemServiceForLookup;
    private final IntegrationAccountService integrationAccountService;

    @Override
    public boolean supports(IntegrationEntityType entityType) {
        return entityType == IntegrationEntityType.PURCHASE_ORDER;
    }

    @Override
    public DependencyPushResult ensureDependencies(
            IntegrationConnection connection,
            List<?> entities) {

        @SuppressWarnings("unchecked")
        List<PurchaseOrder> pos = (List<PurchaseOrder>) entities;

        log.debug("Ensuring dependencies for {} purchase orders", pos.size());

        // Extract unique ship-tos and vendors from POs
        Set<String> shipToIds = extractShipTos(pos);
        Set<String> vendorIds = extractVendors(pos);

        // Extract unique items and accounts from PO line items
        Set<String> itemIds = extractItemIds(pos);
        Set<String> accountIds = extractAccountIds(pos);


        List<Party> shipTos = shipToIds.isEmpty()
                ? List.of() : contactSyncService.findByIds(List.copyOf(shipToIds));

        List<Party> vendors = vendorIds.isEmpty()
                ? List.of() : contactSyncService.findByIds(List.copyOf(vendorIds));

        // Batch fetch items and accounts
        List<Item> items = itemIds.isEmpty()
            ? List.of()
            : itemServiceForLookup.findByIds(List.copyOf(itemIds));

        List<IntegrationAccount> accounts = accountIds.isEmpty()
            ? List.of()
            : integrationAccountService.findByIds(connection.getId(), List.copyOf(accountIds));

        // CORRECT ORDER (fail-fast):

        // 0. Ship-tos first (no dependencies)
        if (!shipTos.isEmpty()) {
            log.debug("Ensuring {} ship-tos have external IDs", shipTos.size());
            DependencyPushResult result = customerService.ensureHaveExternalIds(connection, shipTos);
            if (!result.isSuccess()) {
                log.error("Ship-to dependency check failed: {}", result.getMessage());
                return result; // FAIL FAST
            }
        }

        // 1. Vendors next (no dependencies)
        if (!vendors.isEmpty()) {
            log.debug("Ensuring {} vendors have external IDs", vendors.size());
            DependencyPushResult result = vendorService.ensureHaveExternalIds(connection, vendors);
            if (!result.isSuccess()) {
                log.error("Vendor dependency check failed: {}", result.getMessage());
                return result; // FAIL FAST
            }
        }

        // 2. Accounts (validate only - synced FROM provider)
        if (!accounts.isEmpty()) {
            log.debug("Validating {} accounts have external IDs", accounts.size());
            DependencyPushResult result = accountService.validateHaveExternalIds(connection, accounts);
            if (!result.isSuccess()) {
                log.error("Account dependency validation failed: {}", result.getMessage());
                return result; // FAIL FAST
            }
        }

        // 3. Items (may reference accounts)
        if (!items.isEmpty()) {
            log.debug("Ensuring {} items have external IDs", items.size());
            DependencyPushResult result = itemService.ensureHaveExternalIds(connection, items);
            if (!result.isSuccess()) {
                log.error("Item dependency check failed: {}", result.getMessage());
                return result; // FAIL FAST
            }
        }

        log.info("All dependencies ready for {} purchase orders", pos.size());
        return DependencyPushResult.success();
    }

    /**
     * Extract unique ship-tos from purchase orders.
     * Fetches full Party objects from database.
     */
    private Set<String> extractShipTos(List<PurchaseOrder> pos) {
        return pos.stream()
            .map(PurchaseOrder::getShipToContact)
            .filter(Objects::nonNull)
            .map(Party::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /**
     * Extract unique vendors from purchase orders.
     * Fetches full Party objects from database.
     */
    private Set<String> extractVendors(List<PurchaseOrder> pos) {
        return pos.stream()
            .map(PurchaseOrder::getVendorContact)
            .filter(Objects::nonNull)
            .map(Party::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /**
     * Extract unique item IDs from PO line items.
     */
    private Set<String> extractItemIds(List<PurchaseOrder> pos) {
        return pos.stream()
            .filter(po -> po.getItems() != null)
            .flatMap(po -> po.getItems().stream())
            .map(PurchaseOrderItem::getItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /**
     * Extract unique account IDs from PO line items.
     */
    private Set<String> extractAccountIds(List<PurchaseOrder> pos) {
        return pos.stream()
            .filter(po -> po.getItems() != null)
            .flatMap(po -> po.getItems().stream())
            .map(PurchaseOrderItem::getAccountId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }
}
