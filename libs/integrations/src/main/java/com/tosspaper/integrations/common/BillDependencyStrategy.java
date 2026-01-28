package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;

import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.ComparisonResult;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.DocumentPartComparisonService;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dependency strategy for Bills (Invoices).
 * Ensures Vendors and Purchase Orders exist in QBO before pushing the Bill.
 * Logic:
 * 1. Resolve Vendor:
 * - Try to get from linked PO (if exists).
 * - Fallback: Lookup by name in DB.
 * - Fallback: Create new Vendor in DB.
 * 2. Ensure Vendor has external ID (push if needed).
 * 3. Ensure PO has external ID (push if needed).
 * 4. Update Invoice with resolved Vendor external ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillDependencyStrategy implements DependencyStrategy {

    private final VendorDependencyPushService vendorService;
    private final PurchaseOrderDependencyPushService poPushService;
    private final ContactSyncService contactService;
    private final PurchaseOrderSyncService poSyncService;
    private final DocumentPartComparisonService comparisonService;

    @Override
    public boolean supports(IntegrationEntityType entityType) {
        return entityType == IntegrationEntityType.BILL;
    }

    @Override
    public DependencyPushResult ensureDependencies(
            IntegrationConnection connection,
            List<?> entities) {

        @SuppressWarnings("unchecked")
        List<Invoice> invoices = (List<Invoice>) entities;
        log.debug("Ensuring dependencies for {} invoices (bills)", invoices.size());

        Map<String, Party> resolvedVendors = new HashMap<>(); // Invoice ID -> Vendor
        Map<String, PurchaseOrder> resolvedPOs = new HashMap<>(); // Invoice ID -> PO

        // 1. Resolve Vendors and POs for each invoice
        Set<String> allVendorIds = new HashSet<>();
        List<String> poNumbers = new ArrayList<>();
        Map<String, String> invoiceToPoNumber = new HashMap<>();

        // Step 1: Collect PO numbers and validate existence on invoice
        for (Invoice invoice : invoices) {
            String poNumber = invoice.getPoNumber();
            if (poNumber == null || poNumber.isBlank()) {
                return DependencyPushResult
                        .failure("Invoice " + invoice.getDocumentNumber() + " is missing required PO number");
            }
            poNumbers.add(poNumber);
            invoiceToPoNumber.put(invoice.getAssignedId(), poNumber);
        }

        // Step 2: Batch fetch POs
        Long companyId = connection.getCompanyId();
        List<PurchaseOrder> foundPOs = poSyncService.findByCompanyIdAndDisplayIds(companyId, poNumbers);
        Map<String, PurchaseOrder> poMap = foundPOs.stream()
                .collect(Collectors.toMap(PurchaseOrder::getDisplayId, po -> po));

        // Step 3: Link POs and collect Vendor IDs
        for (Invoice invoice : invoices) {
            String invoiceId = invoice.getAssignedId();
            String poNumber = invoiceToPoNumber.get(invoiceId);

            PurchaseOrder po = poMap.get(poNumber);

            if (po == null) {
                return DependencyPushResult.failure("Purchase Order " + poNumber
                        + " not found for Invoice " + invoice.getDocumentNumber());
            }

            resolvedPOs.put(invoiceId, po);

            if (po.getVendorContact() != null && po.getVendorContact().getId() != null) {
                allVendorIds.add(po.getVendorContact().getId());
            } else {
                return DependencyPushResult
                        .failure("Purchase Order " + poNumber + " does not have a linked Vendor");
            }
        }

        // Batch fetch vendors
        if (!allVendorIds.isEmpty()) {
            // We need companyId for lookup, assuming all invoices in batch are for same
            // company or we pick one.
            // Integration connection implies one company.
            List<Party> vendors = contactService.findByIds(new ArrayList<>(allVendorIds));

            // Map vendors by ID for easy lookup
            Map<String, Party> vendorMap = vendors.stream()
                    .collect(Collectors.toMap(Party::getId, v -> v));

            // Populate resolvedVendors
            for (Invoice invoice : invoices) {
                PurchaseOrder po = resolvedPOs.get(invoice.getAssignedId());

                if (po == null || po.getVendorContact() == null || po.getVendorContact().getId() == null) {
                    continue;
                }

                Party vendor = vendorMap.get(po.getVendorContact().getId());
                if (vendor == null) {
                    return DependencyPushResult
                            .failure("Vendor " + po.getVendorContact().getId() + " not found for PO "
                                    + po.getDisplayId());
                }

                resolvedVendors.put(invoice.getAssignedId(), vendor);
            }
        }

        // 2. Collect unique Vendors and POs to push
        List<Party> uniqueVendors = resolvedVendors.values().stream().distinct().collect(Collectors.toList());
        List<PurchaseOrder> uniquePOs = resolvedPOs.values().stream().distinct().collect(Collectors.toList());

        // 3. Ensure Vendors have external IDs
        if (!uniqueVendors.isEmpty()) {
            DependencyPushResult result = vendorService.ensureHaveExternalIds(connection, uniqueVendors);
            if (!result.isSuccess()) {
                return result;
            }
        }

        // NOTE: POs are NOT required to be in QuickBooks for Bills
        // Bills only need:
        // 1. Vendor external_id (handled above)
        // 2. Invoice line items (from invoice, not PO)
        // 3. PO number as text memo (no QB reference needed)
        // The PO is only used to lookup the vendor - we don't push it to QB

        // 4. Update Invoices with resolved External IDs
        for (Invoice invoice : invoices) {
            String invoiceId = invoice.getAssignedId();

            // Link Vendor External ID
            Party vendor = resolvedVendors.get(invoiceId);

            if (vendor == null || vendor.getExternalId() == null) {
                return DependencyPushResult
                        .failure("Could not resolve Vendor for Invoice " + invoice.getDocumentNumber());
            }

            if (invoice.getSellerInfo() == null) {
                return DependencyPushResult.failure(
                        "Invoice " + invoice.getDocumentNumber() + " is missing seller info");
            }
            invoice.getSellerInfo().setReferenceNumber(vendor.getExternalId());
            // Also align name to match exactly what's in QBO/System
            invoice.getSellerInfo().setName(vendor.getName());

            // 5. Enrich invoice line items with external IDs from PO line items
            PurchaseOrder po = resolvedPOs.get(invoiceId);
            if (po != null && po.getItems() != null && invoice.getLineItems() != null) {
                enrichInvoiceLineItems(connection, invoice, po);
            }
        }

        return DependencyPushResult.success();
    }

    /**
     * Enrich invoice line items with external IDs from matched PO line items.
     * Uses AI-generated matches from document_part_comparisons table.
     */
    private void enrichInvoiceLineItems(IntegrationConnection connection, Invoice invoice, PurchaseOrder po) {
        // Retrieve comparison result
        Optional<Comparison> comparisonOpt =
            comparisonService.getComparisonByAssignedId(invoice.getAssignedId(), connection.getCompanyId());

        if (comparisonOpt.isEmpty() || comparisonOpt.get().getResults() == null) {
            log.warn("No comparison result found for invoice {} - cannot enrich line items",
                invoice.getDocumentNumber());
            return;
        }

        // Filter for matched line items with valid PO index
        List<ComparisonResult> matches = comparisonOpt.get().getResults().stream()
            .filter(r -> r.getType() == ComparisonResult.Type.LINE_ITEM)
            .filter(r -> r.getPoIndex() != null)
            .toList();

        if (matches.isEmpty()) {
            log.warn("No AI matches found for invoice {} - cannot enrich line items",
                invoice.getDocumentNumber());
            return;
        }

        log.info("Found {} AI-matched line items for invoice {}",
            matches.size(), invoice.getDocumentNumber());

        // Map each matched line item
        for (ComparisonResult match : matches) {
            Integer invoiceIndex = match.getExtractedIndex() != null
                    ? match.getExtractedIndex().intValue() : null;
            Integer poIndex = match.getPoIndex() != null
                    ? match.getPoIndex().intValue() : null;

            // Validate indices
            if (invoiceIndex == null || poIndex == null) {
                log.warn("Skipping match with null indices: invoiceIndex={}, poIndex={}",
                    invoiceIndex, poIndex);
                continue;
            }
            if (invoiceIndex < 0 || poIndex < 0) {
                log.warn("Skipping match with negative indices: invoiceIndex={}, poIndex={}",
                    invoiceIndex, poIndex);
                continue;
            }

            if (invoiceIndex >= invoice.getLineItems().size()) {
                log.warn("Invoice line index {} out of bounds (size: {})",
                    invoiceIndex, invoice.getLineItems().size());
                continue;
            }

            if (poIndex >= po.getItems().size()) {
                log.warn("PO line index {} out of bounds (size: {})",
                    poIndex, po.getItems().size());
                continue;
            }

            // Get the invoice and PO line items
            com.tosspaper.models.domain.LineItem invoiceItem = invoice.getLineItems().get(invoiceIndex);
            com.tosspaper.models.domain.PurchaseOrderItem poItem = po.getItems().get(poIndex);

            // Copy external IDs from matched PO line item
            invoiceItem.setExternalItemId(poItem.getExternalItemId());
            invoiceItem.setExternalAccountId(poItem.getExternalAccountId());

            log.debug("Matched invoice line {} '{}' to PO line {} '{}' (matchScore: {}, externalItemId: {}, externalAccountId: {})",
                invoiceIndex, invoiceItem.getDescription(),
                poIndex, poItem.getName(),
                match.getMatchScore(),
                poItem.getExternalItemId(),
                poItem.getExternalAccountId());
        }

        // Warn if some invoice items were not matched
        long unmatchedCount = invoice.getLineItems().stream()
            .filter(item -> item.getExternalItemId() == null && item.getExternalAccountId() == null)
            .count();

        if (unmatchedCount > 0) {
            log.warn("{} invoice line items have no external IDs - may cause bill push to fail",
                unmatchedCount);
        }
    }
}
