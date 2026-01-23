package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.service.ContactSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enriches purchase order vendor and ship-to contacts with full Party details from database.
 *
 * During pull operations, QuickBooks only provides limited contact info (VendorRef with name/ID).
 * This enricher looks up the full contact details (id, phone, email, notes, status, currency)
 * from our database using the external ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderContactEnricher {

    private final ContactSyncService contactSyncService;
    private final com.tosspaper.models.service.PurchaseOrderSyncService purchaseOrderSyncService;

    /**
     * Enriches vendor and ship-to contacts in purchase orders with full Party details from database.
     *
     * @param companyId the company ID
     * @param provider the integration provider (e.g., QUICKBOOKS)
     * @param purchaseOrders list of purchase orders to enrich
     */
    public void enrichContacts(Long companyId, IntegrationProvider provider, List<PurchaseOrder> purchaseOrders) {
        if (purchaseOrders == null || purchaseOrders.isEmpty()) {
            return;
        }

        // Collect all unique vendor and ship-to external IDs
        Set<String> contactExternalIds = new HashSet<>();

        for (PurchaseOrder po : purchaseOrders) {
            if (po.getVendorContact() != null && po.getVendorContact().getExternalId() != null) {
                contactExternalIds.add(po.getVendorContact().getExternalId());
            }
            if (po.getShipToContact() != null && po.getShipToContact().getExternalId() != null) {
                contactExternalIds.add(po.getShipToContact().getExternalId());
            }
        }

        if (contactExternalIds.isEmpty()) {
            log.debug("No contacts to enrich for {} purchase orders", purchaseOrders.size());
            return;
        }

        // Batch-fetch all contacts from database
        List<Party> contacts = contactSyncService.findByProviderAndExternalIds(
                companyId,
                provider.getValue(),
                new ArrayList<>(contactExternalIds)
        );

        // Create lookup map: externalId -> Party
        Map<String, Party> contactMap = contacts.stream()
                .filter(party -> party.getExternalId() != null)
                .collect(Collectors.toMap(
                        Party::getExternalId,
                        party -> party,
                        (existing, replacement) -> existing)); // Keep first occurrence

        log.debug("Enriching {} purchase orders with {} contacts from database",
                purchaseOrders.size(), contactMap.size());

        // Enrich each PO's contacts
        int vendorsEnriched = 0;
        int shipToEnriched = 0;

        for (PurchaseOrder po : purchaseOrders) {
            // Enrich vendor contact
            if (po.getVendorContact() == null) {
                log.info("No vendor contact to enrich for PO {}", po.getExternalId());
            } else if (po.getVendorContact().getExternalId() == null) {
                log.info("Enriching vendor for PO {}: hasExternalId={}, currentName={}, vendorId={}",
                        po.getExternalId(),
                        false,
                        po.getVendorContact().getName(),
                        po.getVendorContact().getId());
                log.warn("Vendor contact has no external ID for PO {}", po.getExternalId());
            } else {
                log.info("Enriching vendor for PO {}: hasExternalId={}, currentName={}, vendorId={}",
                        po.getExternalId(),
                        true,
                        po.getVendorContact().getName(),
                        po.getVendorContact().getId());

                Party dbVendor = contactMap.get(po.getVendorContact().getExternalId());
                if (dbVendor == null) {
                    log.warn("Vendor external ID {} not found in DB for PO {}",
                            po.getVendorContact().getExternalId(), po.getExternalId());
                } else {
                    log.info("Enriching vendor by external ID for PO {}: dbName={}, dbId={}",
                            po.getExternalId(), dbVendor.getName(), dbVendor.getId());
                    enrichPartyWithFullDetails(po.getVendorContact(), dbVendor);
                    vendorsEnriched++;
                    log.info("After enrichment vendor for PO {}: name={}, id={}",
                            po.getExternalId(), po.getVendorContact().getName(), po.getVendorContact().getId());
                }
            }

            // Enrich ship-to contact (independent of vendor enrichment)
            if (po.getShipToContact() == null) {
                log.info("No ship-to contact to enrich for PO {}", po.getExternalId());
                continue;
            }

            log.info("Enriching ship-to for PO {}: hasExternalId={}, hasAddress={}, currentName={}",
                    po.getExternalId(),
                    po.getShipToContact().getExternalId() != null,
                    po.getShipToContact().getAddress() != null,
                    po.getShipToContact().getName());

            // Try to enrich by external ID first (if ship-to is a synced contact)
            if (po.getShipToContact().getExternalId() != null) {
                Party dbShipTo = contactMap.get(po.getShipToContact().getExternalId());
                if (dbShipTo != null) {
                    log.info("Enriching ship-to by external ID for PO {}: dbName={}",
                            po.getExternalId(), dbShipTo.getName());
                    enrichPartyWithFullDetails(po.getShipToContact(), dbShipTo);
                    shipToEnriched++;
                    continue;
                }
            }

            // Ship-to doesn't have external ID - try fallback lookups
            if (po.getExternalId() == null) {
                log.warn("Cannot enrich ship-to for PO {}: no externalId and no PO externalId", po.getDisplayId());
                continue;
            }

            // Ship-to doesn't have external ID - look up existing PO from DB
            log.info("Looking up existing PO {} to enrich ship-to contact", po.getExternalId());

            PurchaseOrder existingPo = purchaseOrderSyncService.findByProviderAndExternalId(
                    companyId, provider.getValue(), po.getExternalId());

            if (existingPo != null && existingPo.getShipToContact() != null) {
                Party existingShipTo = existingPo.getShipToContact();
                log.info("Found existing ship-to for PO {}: name={}, address={}, id={}",
                        po.getExternalId(), existingShipTo.getName(), existingShipTo.getAddress(), existingShipTo.getId());
                
                // If the existing ship-to contact has an id, look up the customer from contacts table
                Party dbCustomer = null;
                if (existingShipTo.getId() != null) {
                    dbCustomer = contactSyncService.findById(existingShipTo.getId());
                    if (dbCustomer != null) {
                        log.info("Found customer {} for ship-to contact id {}", dbCustomer.getId(), existingShipTo.getId());
                    } else {
                        log.warn("Customer not found for ship-to contact id {}", existingShipTo.getId());
                    }
                }
                
                enrichPartyWithFullDetails(po.getShipToContact(), dbCustomer != null ? dbCustomer : existingShipTo);
                shipToEnriched++;
                log.info("After enrichment ship-to name for PO {}: {}",
                        po.getExternalId(), po.getShipToContact().getName());
                continue;
            }

            // Try fallback lookup by displayId if lookup by externalId failed
            if (po.getDisplayId() == null) {
                log.warn("No existing PO or ship-to found in DB for PO {} (no displayId for fallback)", po.getExternalId());
                continue;
            }

            log.info("Trying fallback lookup by displayId {} for PO {}", po.getDisplayId(), po.getExternalId());

            List<PurchaseOrder> existingPos = purchaseOrderSyncService.findByCompanyIdAndDisplayIds(
                    companyId, List.of(po.getDisplayId()));
            if (existingPos.isEmpty()) {
                log.warn("No existing PO found by displayId {} for PO {}", po.getDisplayId(), po.getExternalId());
                continue;
            }

            PurchaseOrder existingPoByDisplayId = existingPos.get(0);
            if (existingPoByDisplayId.getShipToContact() == null) {
                log.warn("Existing PO {} found by displayId but has no ship-to contact", po.getDisplayId());
                continue;
            }

            Party existingShipToByDisplayId = existingPoByDisplayId.getShipToContact();
            log.info("Found existing ship-to by displayId for PO {}: name={}, id={}",
                    po.getDisplayId(), existingShipToByDisplayId.getName(), existingShipToByDisplayId.getId());
            
            // If the existing ship-to contact has an id, look up the customer from contacts table
            Party dbCustomerByDisplayId = null;
            if (existingShipToByDisplayId.getId() != null) {
                dbCustomerByDisplayId = contactSyncService.findById(existingShipToByDisplayId.getId());
                if (dbCustomerByDisplayId != null) {
                    log.info("Found customer {} for ship-to contact id {}", dbCustomerByDisplayId.getId(), existingShipToByDisplayId.getId());
                } else {
                    log.warn("Customer not found for ship-to contact id {}", existingShipToByDisplayId.getId());
                }
            }
            
            enrichPartyWithFullDetails(po.getShipToContact(), dbCustomerByDisplayId != null ? dbCustomerByDisplayId : existingShipToByDisplayId);
            shipToEnriched++;
        }

        log.info("Enriched {} vendors and {} ship-to contacts for {} purchase orders",
                vendorsEnriched, shipToEnriched, purchaseOrders.size());
    }

    /**
     * Copies all fields from the database Party to the PO's contact Party.
     * Preserves the name and address that came from QuickBooks, but adds all other fields
     * from the database (id, phone, email, notes, status, currency, etc.).
     */
    private void enrichPartyWithFullDetails(Party targetParty, Party dbParty) {
        // Copy database fields
        targetParty.setId(dbParty.getId());
        targetParty.setCompanyId(dbParty.getCompanyId());
        targetParty.setPhone(dbParty.getPhone());
        targetParty.setEmail(dbParty.getEmail());
        targetParty.setNotes(dbParty.getNotes());
        targetParty.setStatus(dbParty.getStatus());
        targetParty.setCurrencyCode(dbParty.getCurrencyCode());
        targetParty.setProvider(dbParty.getProvider());
        targetParty.setProviderVersion(dbParty.getProviderVersion());
        targetParty.setProviderCreatedAt(dbParty.getProviderCreatedAt());
        targetParty.setProviderLastUpdatedAt(dbParty.getProviderLastUpdatedAt());
        targetParty.setExternalMetadata(dbParty.getExternalMetadata());
        targetParty.setCreatedAt(dbParty.getCreatedAt());
        targetParty.setUpdatedAt(dbParty.getUpdatedAt());

        // Use database name if QB didn't provide one (fallback)
        if (targetParty.getName() == null || targetParty.getName().isBlank()) {
            targetParty.setName(dbParty.getName());
        }

        // Use database address if QB didn't provide one (fallback)
        if (targetParty.getAddress() == null && dbParty.getAddress() != null) {
            targetParty.setAddress(dbParty.getAddress());
        }
    }
}
