package com.tosspaper.aiengine.vfs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class VFSContextMapper {
    private final ObjectMapper objectMapper;
    /**
     * Create context from ExtractionTask and PO number.
     */
    public static VfsDocumentContext from(ExtractionTask task) {
        return VfsDocumentContext.builder()
                .companyId(task.getCompanyId())
                .poNumber(task.getPoNumber())
                .documentId(task.getAssignedId())
                .documentType(task.getDocumentType())
                .content(task.getConformedJson())
                .build();
    }

    /**
     * Create context from PurchaseOrder with stripped metadata.
     * Removes sync/provider fields to reduce token usage (~1,000 tokens saved).
     */
    public VfsDocumentContext from(PurchaseOrder po) {
        // Build stripped-down PO map with only comparison-relevant fields
        Map<String, Object> strippedPo = stripPurchaseOrder(po);

        String content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(strippedPo);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize PO for VFS context. companyId={}, poDisplayId={}",
                    po.getCompanyId(), po.getDisplayId(), e);
            throw new IllegalStateException(
                    "Unable to build VFS context for purchase order " + po.getDisplayId(), e);
        }

        return VfsDocumentContext.builder()
                .companyId(po.getCompanyId())
                .poNumber(po.getDisplayId())
                .documentId("po")
                .documentType(DocumentType.PURCHASE_ORDER)
                .content(content)
                .build();
    }

    /**
     * Strip unnecessary metadata from PurchaseOrder for AI comparison.
     * Keeps only fields needed for document matching.
     */
    private Map<String, Object> stripPurchaseOrder(PurchaseOrder po) {
        Map<String, Object> stripped = new LinkedHashMap<>();

        // Keep essential identifiers
        stripped.put("displayId", po.getDisplayId());

        // Keep dates for context
        if (po.getOrderDate() != null) stripped.put("orderDate", po.getOrderDate().toString());
        if (po.getDueDate() != null) stripped.put("dueDate", po.getDueDate().toString());

        // Keep currency
        if (po.getCurrencyCode() != null) stripped.put("currencyCode", po.getCurrencyCode().name());

        // Keep contacts with stripped metadata
        if (po.getVendorContact() != null) {
            stripped.put("vendorContact", stripParty(po.getVendorContact()));
        }
        if (po.getShipToContact() != null) {
            stripped.put("shipToContact", stripParty(po.getShipToContact()));
        }

        // Keep items with stripped metadata
        if (po.getItems() != null && !po.getItems().isEmpty()) {
            List<Map<String, Object>> strippedItems = po.getItems().stream()
                    .map(this::stripPurchaseOrderItem)
                    .collect(Collectors.toList());
            stripped.put("items", strippedItems);
        }

        return stripped;
    }

    /**
     * Strip unnecessary fields from Party (vendor/shipTo contact).
     */
    private Map<String, Object> stripParty(Party party) {
        Map<String, Object> stripped = new LinkedHashMap<>();

        // Keep only comparison-relevant fields
        if (party.getName() != null) stripped.put("name", party.getName());
        if (party.getEmail() != null) stripped.put("email", party.getEmail());
        if (party.getPhone() != null) stripped.put("phone", party.getPhone());

        // Keep address details
        Address address = party.getAddress();
        if (address != null) {
            Map<String, Object> strippedAddress = new LinkedHashMap<>();
            if (address.getAddress() != null) strippedAddress.put("address", address.getAddress());
            if (address.getCity() != null) strippedAddress.put("city", address.getCity());
            if (address.getStateOrProvince() != null) strippedAddress.put("stateOrProvince", address.getStateOrProvince());
            if (address.getPostalCode() != null) strippedAddress.put("postalCode", address.getPostalCode());
            if (address.getCountry() != null) strippedAddress.put("country", address.getCountry());
            if (!strippedAddress.isEmpty()) {
                stripped.put("address", strippedAddress);
            }
        }

        return stripped;
    }

    /**
     * Strip unnecessary fields from PurchaseOrderItem.
     */
    private Map<String, Object> stripPurchaseOrderItem(PurchaseOrderItem item) {
        Map<String, Object> stripped = new LinkedHashMap<>();

        // Keep only comparison-relevant fields
        if (item.getName() != null) stripped.put("name", item.getName());
        if (item.getQuantity() != null) stripped.put("quantity", item.getQuantity());
        if (item.getUnit() != null) stripped.put("unit", item.getUnit());
        if (item.getUnitCode() != null) stripped.put("unitCode", item.getUnitCode());
        if (item.getUnitPrice() != null) stripped.put("unitPrice", item.getUnitPrice());
        if (item.getTotalPrice() != null) stripped.put("totalPrice", item.getTotalPrice());

        if (item.getItemCode() != null) stripped.put("itemCode", item.getItemCode());

        // Omit: id, taxable, expectedDeliveryDate, deliveryStatus, notes, metadata,
        // itemId, accountId, externalAccountId

        return stripped;
    }
}
