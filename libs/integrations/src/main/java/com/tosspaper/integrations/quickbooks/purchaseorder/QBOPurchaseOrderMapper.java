package com.tosspaper.integrations.quickbooks.purchaseorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.data.*;
import com.tosspaper.models.domain.*;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class QBOPurchaseOrderMapper {

    private static final ObjectMapper QBO_OBJECT_MAPPER = new ObjectMapper();

    /**
     * Convert PurchaseOrder to QBO PurchaseOrder.
     * First deserializes stored QBO entity from metadata (if exists) to preserve
     * QB-only fields,
     * then applies domain values on top. Handles both CREATE and UPDATE.
     */
    public com.intuit.ipp.data.PurchaseOrder toQboPurchaseOrder(PurchaseOrder domainPo) {
        if (domainPo == null) {
            return null;
        }

        // First: try to deserialize stored QBO entity (preserves lines, refs, etc.)
        com.intuit.ipp.data.PurchaseOrder qboPo = deserializeStoredQboPurchaseOrder(domainPo);
        if (domainPo.getItems() != null) {
            qboPo.setLine(mapToQboLines(domainPo.getItems()));
        }

        // For UPDATE: set Id and SyncToken
        if (domainPo.isUpdatable()) {
            qboPo.setId(domainPo.getExternalId());
            qboPo.setSyncToken(domainPo.getProviderVersion());
        }

        // Apply domain values
        if (domainPo.getVendorContact() == null || domainPo.getVendorContact().getExternalId() == null) {
            throw new IllegalStateException("VendorContact is required for PurchaseOrder");
        }

        ReferenceType vendorRef = new ReferenceType();
        vendorRef.setValue(domainPo.getVendorContact().getExternalId());
        qboPo.setVendorRef(vendorRef);


        qboPo.setDocNumber(domainPo.getDisplayId());

        if (domainPo.getOrderDate() != null) {
            qboPo.setTxnDate(Date.from(domainPo.getOrderDate().atStartOfDay().toInstant(ZoneOffset.UTC)));
        }

        if (domainPo.getDueDate() != null) {
            qboPo.setDueDate(Date.from(domainPo.getDueDate().atStartOfDay().toInstant(ZoneOffset.UTC)));
        }

        qboPo.setPrivateNote(domainPo.getNotes());

        // Map domain status to QuickBooks status
        if (domainPo.getStatus() != null) {
            qboPo.setPOStatus(mapDomainStatusToQbo(domainPo.getStatus()));
        }

        // Map currency
        if (domainPo.getCurrencyCode() != null) {
            ReferenceType currencyRef = new ReferenceType();
            currencyRef.setValue(domainPo.getCurrencyCode().getCode());
            qboPo.setCurrencyRef(currencyRef);
        }

        // Map ship-to contact
        if (domainPo.getShipToContact() != null) {
            Party shipTo = domainPo.getShipToContact();

            // Set shipTo reference if we have an external ID
            if (shipTo.getExternalId() != null) {
                ReferenceType shipToRef = new ReferenceType();
                shipToRef.setValue(shipTo.getExternalId());
                if (shipTo.getName() != null) {
                    shipToRef.setName(shipTo.getName());
                }
                qboPo.setShipTo(shipToRef);
            }

            // Set ship address if we have address data
            if (shipTo.getAddress() != null) {
                PhysicalAddress shipAddr = mapAddressToPhysicalAddress(shipTo.getAddress());
                qboPo.setShipAddr(shipAddr);
            }
        }

        return qboPo;
    }

    /**
     * Map domain PurchaseOrderStatus to QuickBooks PurchaseOrderStatusEnum.
     * QuickBooks only supports OPEN and CLOSED statuses.
     * 
     * @param domainStatus the domain status
     * @return QuickBooks status enum
     */
    public PurchaseOrderStatusEnum mapDomainStatusToQbo(PurchaseOrderStatus domainStatus) {
        if (domainStatus == null) {
            return null; // QuickBooks will public to OPEN
        }

        return switch (domainStatus) {
            case CLOSED, COMPLETED, CANCELLED -> PurchaseOrderStatusEnum.CLOSED;
            case PENDING, IN_PROGRESS, OPEN -> PurchaseOrderStatusEnum.OPEN;
        };
    }

    /**
     * Deserialize stored QBO entity from externalMetadata.
     * Returns null if not found or deserialization fails.
     */
    public com.intuit.ipp.data.PurchaseOrder deserializeStoredQboPurchaseOrder(PurchaseOrder domainPo) {
        if (domainPo.getExternalMetadata() == null) {
            return new com.intuit.ipp.data.PurchaseOrder();
        }
        Object qboEntityJson = domainPo.getExternalMetadata().get("qboEntity");
        if (qboEntityJson == null) {
            return new com.intuit.ipp.data.PurchaseOrder();
        }
        try {
            return QBO_OBJECT_MAPPER.readValue(qboEntityJson.toString(), com.intuit.ipp.data.PurchaseOrder.class);
        } catch (Exception e) {
            // Fall back to fresh PO
            return new com.intuit.ipp.data.PurchaseOrder();
        }
    }

    public List<Line> mapToQboLines(List<PurchaseOrderItem> items) {
        return items.stream().map(this::mapToQboLine).collect(Collectors.toList());
    }

    public Line mapToQboLine(PurchaseOrderItem item) {
        // Deserialize stored QBO line (preserves refs, detail type, etc.)
        // The stream handler should have already enriched metadata with
        // itemRef/accountRef based on itemId/accountId
        Line line = deserializeStoredQboLine(item);

        // Apply domain field changes (description and amount)
        line.setDescription(item.getName());

        if (item.getTotalPrice() != null) {
            line.setAmount(item.getTotalPrice().setScale(2, java.math.RoundingMode.HALF_UP));
        } else if (item.getUnitPrice() != null && item.getQuantity() != null) {
            line.setAmount(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }

        // Update qty/unitPrice in detail if item-based (for updates to existing lines)
        if (line.getDetailType() == LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL &&
                line.getItemBasedExpenseLineDetail() != null) {
            ItemBasedExpenseLineDetail detail = line.getItemBasedExpenseLineDetail();
            if (item.getQuantity() != null) {
                detail.setQty(BigDecimal.valueOf(item.getQuantity()));
            }
            if (item.getUnitPrice() != null) {
                detail.setUnitPrice(item.getUnitPrice());
            }
        }

        return line;
    }

    public Line deserializeStoredQboLine(PurchaseOrderItem item) {
        Line line = new Line();

        if (item.getMetadata() == null) {
            return line;
        }

        // Try to deserialize full QBO line from metadata
        Object qboLineJson = item.getMetadata().get("qboLine");
        if (qboLineJson != null) {
            try {
                return QBO_OBJECT_MAPPER.readValue(qboLineJson.toString(), Line.class);
            } catch (Exception e) {
                // Fall through to ref-based restoration
            }
        }

        // Fallback: restore from individual refs (for backward compatibility or
        // stream-handler enriched data)
        if (item.getExternalItemId() != null) {
            line.setDetailType(LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL);
            ItemBasedExpenseLineDetail detail = new ItemBasedExpenseLineDetail();
            ReferenceType reference = new ReferenceType();
            reference.setValue(item.getExternalItemId());
            detail.setItemRef(reference);
            line.setItemBasedExpenseLineDetail(detail);
        } else if (item.getExternalAccountId() != null) {
            line.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
            AccountBasedExpenseLineDetail detail = new AccountBasedExpenseLineDetail();
            ReferenceType reference = new ReferenceType();
            reference.setValue(item.getExternalAccountId());
            detail.setAccountRef(reference);
            line.setAccountBasedExpenseLineDetail(detail);
        } else {
            throw new IllegalStateException("Unable to restore line from metadata: " + item.getMetadata());
        }

        return line;
    }

    public PurchaseOrder toDomain(
            com.intuit.ipp.data.PurchaseOrder qboPo,
            String connectionId,
            Currency defaultCurrency) {
        if (qboPo == null)
            return null;

        // Map currency: try QB PO CurrencyRef first, then connection default
        Currency currency = null;
        if (qboPo.getCurrencyRef() != null && qboPo.getCurrencyRef().getValue() != null) {
            currency = Currency.fromQboValue(qboPo.getCurrencyRef().getValue());
            log.debug("Using currency from QB PO CurrencyRef: {}", currency);
        }
        if (currency == null && defaultCurrency != null) {
            currency = defaultCurrency;
            log.debug("Using currency from connection default: {}", currency);
        }
        if (currency == null) {
            log.warn("No currency from QB PO or connection for docNumber={}, defaulting to USD", qboPo.getDocNumber());
            currency = Currency.USD;
        }

        var builder = PurchaseOrder.builder()
                .displayId(qboPo.getDocNumber())
                .status(mapStatus(qboPo.getPOStatus()))
                .orderDate(dateToOffsetDateTime(qboPo.getTxnDate()))
                .dueDate(dateToOffsetDateTime(qboPo.getDueDate()))
                .notes(combineNotes(qboPo.getPrivateNote(), qboPo.getMemo()))
                .vendorContact(mapVendorContact(qboPo))
                .shipToContact(mapShipToContact(qboPo))
                .items(mapLineItems(qboPo.getLine(), connectionId))
                .metadata(buildMetadata(qboPo))
                .currencyCode(currency);

        PurchaseOrder po = builder.build();

        // Set itemsCount based on items list
        if (po.getItems() != null) {
            po.setItemsCount(po.getItems().size());
        }

        // Set ProviderTracked fields (not in builder)
        po.setExternalId(qboPo.getId());
        po.setProvider(IntegrationProvider.QUICKBOOKS.getValue()); // Set provider for synced entities
        po.setProviderVersion(qboPo.getSyncToken());
        if (qboPo.getMetaData() != null) {
            po.setProviderCreatedAt(dateToLocalDate(qboPo.getMetaData().getCreateTime()));
            po.setProviderLastUpdatedAt(dateToLocalDate(qboPo.getMetaData().getLastUpdatedTime()));
        }

        // Store the full QBO entity so we can merge updates without dropping fields.
        // Persisted as JSONB via external_metadata.
        try {
            Map<String, Object> externalMetadata = new HashMap<>();
            externalMetadata.put("qboEntity", QBO_OBJECT_MAPPER.writeValueAsString(qboPo));
            po.setExternalMetadata(externalMetadata);
        } catch (Exception ignored) {
            // Best-effort; mapping should still succeed even if serialization fails.
        }

        return po;
    }

    public PurchaseOrderStatus mapStatus(PurchaseOrderStatusEnum status) {
        if (status == null)
            return PurchaseOrderStatus.OPEN;
        return status == PurchaseOrderStatusEnum.CLOSED ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.OPEN;
    }

    public String combineNotes(String privateNote, String memo) {
        if (privateNote == null && memo == null)
            return null;
        if (privateNote == null)
            return memo;
        if (memo == null)
            return privateNote;
        return privateNote + "\n" + memo;
    }

    public Party mapVendorContact(com.intuit.ipp.data.PurchaseOrder qboPo) {
        if (qboPo.getVendorRef() == null)
            return null;

        Party.PartyBuilder builder = Party.builder()
                .tag(PartyTag.SUPPLIER)
                .name(qboPo.getVendorRef().getName());

        // Map vendor address
        if (qboPo.getVendorAddr() != null) {
            builder.address(mapPhysicalAddressToAddress(qboPo.getVendorAddr()));
        }

        Party party = builder.build();

        // Set externalId if available (not in builder, inherited from ProviderTracked)
        String vendorId = qboPo.getVendorRef().getValue();
        if (vendorId != null && party != null) {
            party.setExternalId(vendorId);
        }

        return party;
    }

    private static final String JOB_LOCATION_MARKER = "[Job Location] ";

    public Party mapShipToContact(com.intuit.ipp.data.PurchaseOrder qboPo) {
        if (qboPo.getShipTo() == null && qboPo.getShipAddr() == null)
            return null;

        Party.PartyBuilder builder = Party.builder();
        builder.tag(PartyTag.SHIP_TO);

        if (qboPo.getShipTo() != null) {
            // Strip [Job Location] prefix from name (added by our system when creating customer)
            String name = qboPo.getShipTo().getName();
            if (name != null && name.startsWith(JOB_LOCATION_MARKER)) {
                name = name.substring(JOB_LOCATION_MARKER.length());
            }
            builder.name(name);
        }

        // Map ship address
        if (qboPo.getShipAddr() != null) {
            builder.address(mapPhysicalAddressToAddress(qboPo.getShipAddr()));
        }

        Party party = builder.build();

        // Set externalId if available (not in builder, inherited from ProviderTracked)
        if (qboPo.getShipTo() != null && party != null) {
            String shipToId = qboPo.getShipTo().getValue();
            if (shipToId != null) {
                party.setExternalId(shipToId);
            }
        }

        return party;
    }

    public List<PurchaseOrderItem> mapLineItems(List<Line> lines, String connectionId) {
        if (lines == null || lines.isEmpty())
            return null;

        return lines.stream()
                .map(line -> mapLineToPurchaseOrderItem(line, connectionId))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public PurchaseOrderItem mapLineToPurchaseOrderItem(Line line, String connectionId) {
        if (line == null)
            return null;

        PurchaseOrderItem.PurchaseOrderItemBuilder builder = PurchaseOrderItem.builder();

        // Map basic line fields
        if (line.getId() != null) {
            builder.id(line.getId());
        }
        if (line.getDescription() != null) {
            builder.name(line.getDescription());
        }
        if (line.getAmount() != null) {
            builder.totalPrice(line.getAmount());
        }

        // Handle different line detail types for domain fields
        // Default to non-taxable; only taxable if TaxCodeRef is present and not "NON"
        Boolean taxable = false;
        if (line.getDetailType() == LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL &&
                line.getItemBasedExpenseLineDetail() != null) {
            ItemBasedExpenseLineDetail detail = line.getItemBasedExpenseLineDetail();
            if (detail.getQty() != null) {
                builder.quantity(detail.getQty().intValue());
            }
            if (detail.getUnitPrice() != null) {
                builder.unitPrice(detail.getUnitPrice());
            }
            // Map taxable from TaxCodeRef - taxable only if TaxCodeRef exists and is not "NON"
            if (detail.getTaxCodeRef() != null && detail.getTaxCodeRef().getValue() != null) {
                String taxCodeValue = detail.getTaxCodeRef().getValue();
                taxable = !"NON".equalsIgnoreCase(taxCodeValue);
            }
            // Set external item ID for resolution during pull
            if (detail.getItemRef() != null && detail.getItemRef().getValue() != null) {
                builder.externalItemId(detail.getItemRef().getValue());
            }
        } else if (line.getDetailType() == LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL &&
                line.getAccountBasedExpenseLineDetail() != null) {
            AccountBasedExpenseLineDetail detail = line.getAccountBasedExpenseLineDetail();
            builder.quantity(0);
            builder.unitPrice(BigDecimal.ZERO);
            // Map taxable from TaxCodeRef - taxable only if TaxCodeRef exists and is not "NON"
            if (detail.getTaxCodeRef() != null && detail.getTaxCodeRef().getValue() != null) {
                String taxCodeValue = detail.getTaxCodeRef().getValue();
                taxable = !"NON".equalsIgnoreCase(taxCodeValue);
            }
            // Set external account ID for resolution during pull
            if (detail.getAccountRef() != null && detail.getAccountRef().getValue() != null) {
                builder.externalAccountId(detail.getAccountRef().getValue());
            }
        }
        builder.taxable(taxable);

        // Serialize full line to metadata (preserves all QB fields for push back)
        Map<String, Object> metadata = new HashMap<>();
        try {
            metadata.put("qboLine", QBO_OBJECT_MAPPER.writeValueAsString(line));
        } catch (Exception e) {
            // Best-effort serialization
        }
        builder.metadata(metadata);

        return builder.build();
    }

    public Address mapPhysicalAddressToAddress(PhysicalAddress physicalAddr) {
        if (physicalAddr == null)
            return null;

        Address.AddressBuilder builder = Address.builder();

        // Combine Line1 and Line2 into address field
        StringBuilder addressLines = new StringBuilder();
        if (physicalAddr.getLine1() != null) {
            addressLines.append(physicalAddr.getLine1());
        }
        if (physicalAddr.getLine2() != null) {
            if (!addressLines.isEmpty()) {
                addressLines.append(", ");
            }
            addressLines.append(physicalAddr.getLine2());
        }
        if (!addressLines.isEmpty()) {
            builder.address(addressLines.toString());
        }

        if (physicalAddr.getCity() != null) {
            builder.city(physicalAddr.getCity());
        }
        if (physicalAddr.getCountrySubDivisionCode() != null) {
            builder.stateOrProvince(physicalAddr.getCountrySubDivisionCode());
        }
        if (physicalAddr.getPostalCode() != null) {
            builder.postalCode(physicalAddr.getPostalCode());
        }
        if (physicalAddr.getCountry() != null) {
            builder.country(physicalAddr.getCountry());
        }

        return builder.build();
    }

    /**
     * Map domain Address to QuickBooks PhysicalAddress.
     * Used when setting ship address on PO from ship-to contact.
     */
    public PhysicalAddress mapAddressToPhysicalAddress(Address address) {
        if (address == null) {
            return null;
        }

        PhysicalAddress physicalAddr = new PhysicalAddress();
        physicalAddr.setLine1(address.getAddress());
        physicalAddr.setCity(address.getCity());
        physicalAddr.setCountry(address.getCountry());
        physicalAddr.setCountrySubDivisionCode(address.getStateOrProvince());
        physicalAddr.setPostalCode(address.getPostalCode());

        return physicalAddr;
    }

    public Map<String, Object> buildMetadata(com.intuit.ipp.data.PurchaseOrder qboPo) {
        Map<String, Object> metadata = new HashMap<>();

        if (qboPo.getTotalAmt() != null) {
            metadata.put("totalAmount", qboPo.getTotalAmt());
        }
        if (qboPo.getSyncToken() != null) {
            metadata.put("syncToken", qboPo.getSyncToken());
        }
        if (qboPo.getAPAccountRef() != null) {
            metadata.put("apAccountRef", mapReference(qboPo.getAPAccountRef()));
        }
        if (qboPo.getCurrencyRef() != null) {
            metadata.put("currencyRef", mapReference(qboPo.getCurrencyRef()));
        }
        if (qboPo.getSalesTermRef() != null) {
            metadata.put("salesTermRef", mapReference(qboPo.getSalesTermRef()));
        }
        if (qboPo.getShipMethodRef() != null) {
            metadata.put("shipMethodRef", mapReference(qboPo.getShipMethodRef()));
        }
        if (qboPo.getClassRef() != null) {
            metadata.put("classRef", mapReference(qboPo.getClassRef()));
        }
        if (qboPo.getPOEmail() != null && qboPo.getPOEmail().getAddress() != null) {
            metadata.put("poEmail", qboPo.getPOEmail().getAddress());
        }
        if (qboPo.getEmailStatus() != null) {
            metadata.put("emailStatus", qboPo.getEmailStatus());
        }
        if (qboPo.getDocNumber() != null) {
            metadata.put("docNumber", qboPo.getDocNumber());
        }

        // Map custom fields
        if (qboPo.getCustomField() != null && !qboPo.getCustomField().isEmpty()) {
            Map<String, Object> customFields = mapCustomFieldsToMetadata(qboPo.getCustomField());
            metadata.put("customFields", customFields);
        }

        return metadata.isEmpty() ? null : metadata;
    }

    public Map<String, Object> mapCustomFieldsToMetadata(List<CustomField> customFields) {
        if (customFields == null || customFields.isEmpty())
            return null;

        Map<String, Object> metadata = new HashMap<>();
        for (CustomField field : customFields) {
            if (field != null && field.getDefinitionId() != null) {
                String key = field.getName() != null ? field.getName() : "field_" + field.getDefinitionId();
                Object value = field.getStringValue() != null ? field.getStringValue()
                        : (field.getType() != null ? field.getType().toString() : null);
                if (value != null) {
                    metadata.put(key, value);
                }
            }
        }
        return metadata.isEmpty() ? null : metadata;
    }

    public Map<String, String> mapReference(ReferenceType ref) {
        if (ref == null)
            return null;
        Map<String, String> refMap = new HashMap<>();
        if (ref.getValue() != null) {
            refMap.put("value", ref.getValue());
        }
        if (ref.getName() != null) {
            refMap.put("name", ref.getName());
        }
        return refMap.isEmpty() ? null : refMap;
    }

    public OffsetDateTime dateToLocalDate(Date date) {
        if (date == null)
            return null;
        return date.toInstant().atOffset(ZoneOffset.UTC);
    }

    public LocalDate dateToOffsetDateTime(Date date) {
        if (date == null)
            return null;
        return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Creates a minimal PurchaseOrder for a deleted record from CDC.
     */
    public PurchaseOrder toDeletedPurchaseOrder(String externalId, OffsetDateTime deletedAt) {
        PurchaseOrder po = PurchaseOrder.builder().build();
        po.setExternalId(externalId);
        po.setProvider(IntegrationProvider.QUICKBOOKS.getValue());
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        po.setDeletedAt(deletedAt);
        return po;
    }

    // ==================== JsonNode (CDC API) to Domain Mapping
    // ====================

    public PurchaseOrderStatus mapJsonStatus(String status) {
        if (status == null)
            return PurchaseOrderStatus.OPEN;
        return "Closed".equalsIgnoreCase(status) ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.OPEN;
    }

    public Party mapJsonVendorContact(JsonNode node) {
        JsonNode vendorRef = node.path("VendorRef");
        if (vendorRef.isMissingNode())
            return null;

        Party.PartyBuilder builder = Party.builder()
                .tag(PartyTag.VENDOR)
                .name(getTextOrNull(vendorRef, "name"));

        // Map vendor address
        JsonNode vendorAddr = node.path("VendorAddr");
        if (!vendorAddr.isMissingNode()) {
            builder.address(mapJsonAddress(vendorAddr));
        }

        Party party = builder.build();

        // Set externalId
        String vendorId = getTextOrNull(vendorRef, "value");
        if (vendorId != null) {
            party.setExternalId(vendorId);
        }

        return party;
    }

    public Party mapJsonShipToContact(JsonNode node) {
        JsonNode shipTo = node.path("ShipTo");
        JsonNode shipAddr = node.path("ShipAddr");

        if (shipTo.isMissingNode() && shipAddr.isMissingNode())
            return null;

        Party.PartyBuilder builder = Party.builder()
                .tag(PartyTag.SHIP_TO);

        if (!shipTo.isMissingNode()) {
            // Strip [Job Location] prefix from name (added by our system when creating customer)
            String name = getTextOrNull(shipTo, "name");
            if (name != null && name.startsWith(JOB_LOCATION_MARKER)) {
                name = name.substring(JOB_LOCATION_MARKER.length());
            }
            builder.name(name);
        }

        if (!shipAddr.isMissingNode()) {
            builder.address(mapJsonAddress(shipAddr));
        }

        Party party = builder.build();

        // Set externalId if available
        if (!shipTo.isMissingNode()) {
            String shipToId = getTextOrNull(shipTo, "value");
            if (shipToId != null) {
                party.setExternalId(shipToId);
            }
        }

        return party;
    }

    public Address mapJsonAddress(JsonNode addrNode) {
        if (addrNode == null || addrNode.isMissingNode())
            return null;

        Address.AddressBuilder builder = Address.builder();

        // Combine Line1, Line2, Line3, Line4 into address field
        StringBuilder addressLines = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            String line = getTextOrNull(addrNode, "Line" + i);
            if (line != null) {
                if (!addressLines.isEmpty()) {
                    addressLines.append(", ");
                }
                addressLines.append(line);
            }
        }
        if (!addressLines.isEmpty()) {
            builder.address(addressLines.toString());
        }

        builder.city(getTextOrNull(addrNode, "City"));
        builder.stateOrProvince(getTextOrNull(addrNode, "CountrySubDivisionCode"));
        builder.postalCode(getTextOrNull(addrNode, "PostalCode"));
        builder.country(getTextOrNull(addrNode, "Country"));

        return builder.build();
    }

    public List<PurchaseOrderItem> mapJsonLineItems(JsonNode linesNode) {
        if (linesNode == null || linesNode.isMissingNode() || !linesNode.isArray())
            return null;

        List<PurchaseOrderItem> items = new ArrayList<>();
        for (JsonNode lineNode : linesNode) {
            PurchaseOrderItem item = mapJsonLineItem(lineNode);
            if (item != null) {
                items.add(item);
            }
        }
        return items.isEmpty() ? null : items;
    }

    public PurchaseOrderItem mapJsonLineItem(JsonNode lineNode) {
        if (lineNode == null || lineNode.isMissingNode())
            return null;

        PurchaseOrderItem.PurchaseOrderItemBuilder builder = PurchaseOrderItem.builder();

        // Basic line fields
        builder.id(getTextOrNull(lineNode, "Id"));
        builder.name(getTextOrNull(lineNode, "Description"));

        String amountStr = getTextOrNull(lineNode, "Amount");
        if (amountStr != null) {
            builder.totalPrice(new BigDecimal(amountStr));
        }

        Map<String, Object> metadata = new HashMap<>();
        // Default to non-taxable; only taxable if TaxCodeRef is present and not "NON"
        Boolean taxable = false;

        // Handle ItemBasedExpenseLineDetail
        JsonNode itemDetail = lineNode.path("ItemBasedExpenseLineDetail");
        if (!itemDetail.isMissingNode()) {
            String qtyStr = getTextOrNull(itemDetail, "Qty");
            if (qtyStr != null) {
                builder.quantity(new BigDecimal(qtyStr).intValue());
            }

            String unitPriceStr = getTextOrNull(itemDetail, "UnitPrice");
            if (unitPriceStr != null) {
                builder.unitPrice(new BigDecimal(unitPriceStr));
            }

            // Store references in metadata
            JsonNode itemRefNode = itemDetail.path("ItemRef");
            addRefToMetadata(metadata, "itemRef", itemRefNode);
            addRefToMetadata(metadata, "customerRef", itemDetail.path("CustomerRef"));
            JsonNode taxCodeRef = itemDetail.path("TaxCodeRef");
            addRefToMetadata(metadata, "taxCodeRef", taxCodeRef);

            // Set external item ID for resolution during pull
            String externalItemId = getTextOrNull(itemRefNode, "value");
            if (externalItemId != null) {
                builder.externalItemId(externalItemId);
            }

            // Map taxable from TaxCodeRef
            String taxCodeValue = getTextOrNull(taxCodeRef, "value");
            if (taxCodeValue != null) {
                taxable = !"NON".equalsIgnoreCase(taxCodeValue);
            }

            String billableStatus = getTextOrNull(itemDetail, "BillableStatus");
            if (billableStatus != null) {
                metadata.put("billableStatus", billableStatus);
            }
        }

        // Handle AccountBasedExpenseLineDetail
        JsonNode accountDetail = lineNode.path("AccountBasedExpenseLineDetail");
        if (!accountDetail.isMissingNode()) {
            builder.quantity(0);
            builder.unitPrice(BigDecimal.ZERO);

            JsonNode accountRefNode = accountDetail.path("AccountRef");
            addRefToMetadata(metadata, "accountRef", accountRefNode);
            addRefToMetadata(metadata, "customerRef", accountDetail.path("CustomerRef"));
            JsonNode taxCodeRef = accountDetail.path("TaxCodeRef");
            addRefToMetadata(metadata, "taxCodeRef", taxCodeRef);

            // Set external account ID for resolution during pull
            String externalAccountId = getTextOrNull(accountRefNode, "value");
            if (externalAccountId != null) {
                builder.externalAccountId(externalAccountId);
            }

            // Map taxable from TaxCodeRef
            String taxCodeValue = getTextOrNull(taxCodeRef, "value");
            if (taxCodeValue != null) {
                taxable = !"NON".equalsIgnoreCase(taxCodeValue);
            }

            String billableStatus = getTextOrNull(accountDetail, "BillableStatus");
            if (billableStatus != null) {
                metadata.put("billableStatus", billableStatus);
            }
        }

        builder.taxable(taxable);

        // Store the full line JSON for round-trip preservation (like we do for SDK
        // objects)
        try {
            metadata.put("qboLine", lineNode.toString());
        } catch (Exception e) {
            // Best-effort serialization
        }

        if (!metadata.isEmpty()) {
            builder.metadata(metadata);
        }

        return builder.build();
    }

    public Map<String, Object> buildJsonMetadata(JsonNode node) {
        Map<String, Object> metadata = new HashMap<>();

        String totalAmt = getTextOrNull(node, "TotalAmt");
        if (totalAmt != null) {
            metadata.put("totalAmount", new BigDecimal(totalAmt));
        }

        String syncToken = getTextOrNull(node, "SyncToken");
        if (syncToken != null) {
            metadata.put("syncToken", syncToken);
        }

        addRefToMetadata(metadata, "apAccountRef", node.path("APAccountRef"));
        addRefToMetadata(metadata, "currencyRef", node.path("CurrencyRef"));
        addRefToMetadata(metadata, "salesTermRef", node.path("SalesTermRef"));
        addRefToMetadata(metadata, "shipMethodRef", node.path("ShipMethodRef"));
        addRefToMetadata(metadata, "classRef", node.path("ClassRef"));

        JsonNode poEmail = node.path("POEmail");
        if (!poEmail.isMissingNode()) {
            String emailAddr = getTextOrNull(poEmail, "Address");
            if (emailAddr != null) {
                metadata.put("poEmail", emailAddr);
            }
        }

        String emailStatus = getTextOrNull(node, "EmailStatus");
        if (emailStatus != null) {
            metadata.put("emailStatus", emailStatus);
        }

        // Map custom fields
        JsonNode customFields = node.path("CustomField");
        if (customFields.isArray() && !customFields.isEmpty()) {
            Map<String, Object> customFieldsMap = new HashMap<>();
            for (JsonNode cf : customFields) {
                String defId = getTextOrNull(cf, "DefinitionId");
                if (defId != null) {
                    String key = getTextOrNull(cf, "Name");
                    if (key == null)
                        key = "field_" + defId;
                    String value = getTextOrNull(cf, "StringValue");
                    if (value != null) {
                        customFieldsMap.put(key, value);
                    }
                }
            }
            if (!customFieldsMap.isEmpty()) {
                metadata.put("customFields", customFieldsMap);
            }
        }

        // Map linked transactions
        JsonNode linkedTxn = node.path("LinkedTxn");
        if (linkedTxn.isArray() && !linkedTxn.isEmpty()) {
            List<Map<String, String>> linkedTxns = new ArrayList<>();
            for (JsonNode txn : linkedTxn) {
                Map<String, String> txnMap = new HashMap<>();
                String txnId = getTextOrNull(txn, "TxnId");
                String txnType = getTextOrNull(txn, "TxnType");
                if (txnId != null)
                    txnMap.put("txnId", txnId);
                if (txnType != null)
                    txnMap.put("txnType", txnType);
                if (!txnMap.isEmpty())
                    linkedTxns.add(txnMap);
            }
            if (!linkedTxns.isEmpty()) {
                metadata.put("linkedTxn", linkedTxns);
            }
        }

        return metadata.isEmpty() ? null : metadata;
    }

    // ==================== JSON Helper Methods ====================

    public String getTextOrNull(JsonNode node, String fieldName) {
        if (node == null)
            return null;
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull())
            return null;
        return field.asText();
    }

    public OffsetDateTime parseJsonDate(String dateStr) {
        if (dateStr == null)
            return null;
        try {
            // Format: "2025-11-07"
            return java.time.LocalDate.parse(dateStr)
                    .atStartOfDay()
                    .atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    public OffsetDateTime parseJsonDateTime(String dateTimeStr) {
        if (dateTimeStr == null)
            return null;
        try {
            // Format: "2025-11-07T13:10:14-08:00"
            return OffsetDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    public void addRefToMetadata(Map<String, Object> metadata, String key, JsonNode refNode) {
        if (refNode == null || refNode.isMissingNode())
            return;
        Map<String, String> refMap = new HashMap<>();
        String value = getTextOrNull(refNode, "value");
        String name = getTextOrNull(refNode, "name");
        if (value != null)
            refMap.put("value", value);
        if (name != null)
            refMap.put("name", name);
        if (!refMap.isEmpty()) {
            metadata.put(key, refMap);
        }
    }
}