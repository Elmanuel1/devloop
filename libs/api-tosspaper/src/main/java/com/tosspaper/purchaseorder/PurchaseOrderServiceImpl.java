package com.tosspaper.purchaseorder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.*;
import com.tosspaper.models.exception.ForbiddenException;
import com.tosspaper.company.CompanyRepository;
import com.tosspaper.generated.model.*;
import com.tosspaper.ingestion.VectorStoreIngestionPublisher;
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import com.tosspaper.models.jooq.tables.records.ProjectsRecord;
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord;
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord;
import com.tosspaper.project.ProjectRepository;
import com.tosspaper.purchaseorder.model.ChangeLogEntry;
import com.tosspaper.purchaseorder.model.PurchaseOrderQuery;
import com.tosspaper.common.exception.ServiceException;
import com.tosspaper.integrations.push.IntegrationPushEvent;
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationCategory;
import com.tosspaper.contact.ContactService;
import com.tosspaper.generated.model.Contact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ProjectRepository projectRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final ObjectMapper objectMapper;
    private final CompanyRepository companyRepository;
    private final VectorStoreIngestionPublisher vectorStoreIngestionPublisher;
    private final IntegrationPushStreamPublisher integrationPushStreamPublisher;
    private final IntegrationConnectionService integrationConnectionService;
    private final ContactService contactService;

    @Override
    public PurchaseOrder getPurchaseOrder(Long companyId, String id) {
        List<PurchaseOrderFlatItemsRecord> poRecord = purchaseOrderRepository.findById(id);
        List<PurchaseOrder> allOrders = purchaseOrderMapper.fromFlatRecords(poRecord);
        if (allOrders.isEmpty()) {
            throw new NotFoundException(ApiErrorMessages.PURCHASE_ORDER_NOT_FOUND_CODE, ApiErrorMessages.PURCHASE_ORDER_NOT_FOUND);
        }

        if (allOrders.size() > 1) {
            log.error("Multiple purchase orders found with the same ID: {}", id);
            throw new ServiceException(ApiErrorMessages.INTERNAL_SERVER_ERROR_CODE, "Multiple purchase orders found with the same ID");
        }

        PurchaseOrder purchaseOrder = allOrders.getFirst();
        if (!Objects.equals(purchaseOrder.getCompanyId(), companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.PURCHASE_ORDER_NOT_FOUND);
        }

        return allOrders.getFirst();
    }

    @Override
    public PurchaseOrderList getPurchaseOrdersByProjectId(Long companyId, String projectId, String displayId, com.tosspaper.purchaseorder.model.PurchaseOrderStatus status, OffsetDateTime dueDate, OffsetDateTime createdDateFrom, OffsetDateTime createdDateTo, Integer page, Integer pageSize, String search) {
        // Convert enum to string for repository layer
        // Note: No need to validate projectId existence - database filter will return empty results if a project doesn't exist
        String statusString = status != null ? status.getValue() : null;

        var query = PurchaseOrderQuery.builder()
                .projectId(projectId)
                .status(statusString)
                .createdDateFrom(createdDateFrom)
                .createdDateTo(createdDateTo)
                .page((page != null && pageSize > 0) ? page : 1)
                .pageSize(pageSize != null ? pageSize : 20)
                .displayId(displayId)
                .dueDate(dueDate)
                .search(search)
                .build();
        
        var purchaseOrderRecords = purchaseOrderRepository.find(companyId, query);
        List<PurchaseOrder> allOrders = purchaseOrderMapper.toDtoListWithoutItems(purchaseOrderRecords);

        // Get total count for pagination metadata
        int totalItems = purchaseOrderRepository.count(companyId, query);
        int totalPages = (int) Math.ceil((double) totalItems / query.getPageSize());

        var paginationDto = new com.tosspaper.generated.model.Pagination()
                .page(query.getPage())
                .pageSize(query.getPageSize())
                .totalPages(totalPages)
                .totalItems(totalItems);

        var result = new PurchaseOrderList();
        result.setData(allOrders);
        result.setPagination(paginationDto);

        return result;
    }

    @Override
    public PurchaseOrder createPurchaseOrder(Long companyId, String projectId, PurchaseOrderCreate purchaseOrderCreate) {
        // Accept Contact objects as-is - no validation
        Contact vendorContact = purchaseOrderCreate.getVendorContact();
        Contact shipToContact = purchaseOrderCreate.getShipToContact();

        // Validate vendor currency if there's an active FINANCIAL connection
        validateVendorCurrency(companyId, vendorContact);

        // Validate total price is not zero
        validateTotalPrice(purchaseOrderCreate.getItems());

        ProjectsRecord project = projectRepository.findById(projectId);

        var record = purchaseOrderMapper.toRecord(companyId, project.getId(), purchaseOrderCreate);

        var items = purchaseOrderCreate.getItems() != null
                ? purchaseOrderMapper.toItemsPojos(purchaseOrderCreate.getItems())
                : Collections.<PurchaseOrderItems>emptyList();

        var createdRecord = purchaseOrderRepository.create(record, items);
        PurchaseOrder purchaseOrder = purchaseOrderMapper.toDto(createdRecord, items);
        
        // Fetch company assigned_email for event enrichment
        String assignedEmail = null;
        try {
            CompaniesRecord company = companyRepository.findById(companyId);
            assignedEmail = company.getAssignedEmail();
        } catch (Exception e) {
            log.warn("Failed to fetch company assigned_email for vector ingestion: companyId={}", companyId, e);
        }
        
        // Publish to vector-store-ingestion stream (non-blocking) with full contact information
        vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, projectId, purchaseOrder, assignedEmail, vendorContact, shipToContact);
        
        // Publish integration push event if there's an active connection
        publishIntegrationPushEventIfNeeded(companyId, purchaseOrder, getCurrentUserEmail());
        
        return purchaseOrder;
    }

    @Override
    public PurchaseOrder updatePurchaseOrder(Long companyId, String id, PurchaseOrderUpdate purchaseOrderUpdate, String authorId) {
        PurchaseOrder purchaseOrder = this.getPurchaseOrder(companyId, id);

        // Prevent updates to provider-synced purchase orders
        if (purchaseOrder.getProvider() != null) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE,
                "Cannot update purchase order synced from provider. Purchase order is managed by external integration.");
        }

        // Get contacts directly from the update request (full Contact objects now)
        Contact vendorContact = purchaseOrderUpdate.getVendorContact();
        Contact shipToContact = purchaseOrderUpdate.getShipToContact();

        // Validate total price is not zero
        if (purchaseOrderUpdate.getItems() != null) {
            validateTotalPrice(purchaseOrderUpdate.getItems());
        }

        List<ChangeLogEntry> changes = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        String existingDisplayId = purchaseOrder.getDisplayId();

        if (purchaseOrderUpdate.getDisplayId() != null && !purchaseOrderUpdate.getDisplayId().isBlank()
                && !purchaseOrderUpdate.getDisplayId().equals(existingDisplayId)) {
            if (purchaseOrder.getStatus() != com.tosspaper.generated.model.PurchaseOrderStatus.PENDING) {
                throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE,
                    ApiErrorMessages.PURCHASE_ORDER_DISPLAY_ID_UPDATE_NOT_ALLOWED);
            }

            changes.add(new ChangeLogEntry(now, authorId, "FIELD_UPDATE",
                "displayId: " + existingDisplayId,
                "displayId: " + purchaseOrderUpdate.getDisplayId(),
                purchaseOrderUpdate.getNotes()));
        }

        if (vendorContact != null) {
            changes.add(new ChangeLogEntry(now, authorId, "FIELD_UPDATE", "vendorContact: " + purchaseOrder.getVendorContact(), "vendorContact: " + vendorContact.getName() + " (" + vendorContact.getId() + ")", purchaseOrderUpdate.getNotes()));
        }
        if (shipToContact != null) {
            changes.add(new ChangeLogEntry(now, authorId, "FIELD_UPDATE", "shipToContact: " + purchaseOrder.getShipToContact(), "shipToContact: " + shipToContact.getName() + " (" + shipToContact.getId() + ")", purchaseOrderUpdate.getNotes()));
        }
        if (purchaseOrderUpdate.getOrderDate() != null && !purchaseOrderUpdate.getOrderDate().equals(purchaseOrder.getOrderDate())) {
            changes.add(new ChangeLogEntry(now, authorId, "FIELD_UPDATE", "orderDate: " + purchaseOrder.getOrderDate(), "orderDate: " + purchaseOrderUpdate.getOrderDate(), purchaseOrderUpdate.getNotes()));
        }
        if (purchaseOrderUpdate.getDueDate() != null && !purchaseOrderUpdate.getDueDate().equals(purchaseOrder.getDueDate())) {
            changes.add(new ChangeLogEntry(now, authorId, "FIELD_UPDATE", "dueDate: " + purchaseOrder.getDueDate(), "dueDate: " + purchaseOrderUpdate.getDueDate(), purchaseOrderUpdate.getNotes()));
        }

        try {
            String oldItems = objectMapper.writeValueAsString(purchaseOrder.getItems() != null ? purchaseOrder.getItems() : Collections.emptyList());
            String newItems = objectMapper.writeValueAsString(purchaseOrderUpdate.getItems());
            if (!oldItems.equals(newItems)) {
                changes.add(new ChangeLogEntry(now, authorId, "ITEMS_UPDATE", oldItems, newItems, "Items have been updated."));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize purchase order {} items for changelog", id, e);
        }


        PurchaseOrdersRecord order = purchaseOrderMapper.toRecord(purchaseOrder);
        purchaseOrderMapper.updateRecordFromDto(purchaseOrderUpdate, order);

        if (purchaseOrderUpdate.getDisplayId() != null && !purchaseOrderUpdate.getDisplayId().isBlank()
                && !purchaseOrderUpdate.getDisplayId().equals(existingDisplayId)) {
            order.setDisplayId(purchaseOrderUpdate.getDisplayId());
        }

        List<PurchaseOrderItems> updatedLineItems = null;
        if (purchaseOrderUpdate.getItems() != null) {
            updatedLineItems = purchaseOrderMapper.toItemsPojos(purchaseOrderUpdate.getItems());
            updatedLineItems.forEach(item -> item.setPurchaseOrderId(id));
            order.setItemsCount(updatedLineItems.size());
        }

        var updatedRecord = purchaseOrderRepository.update(order, updatedLineItems, changes);
        PurchaseOrder updatedPurchaseOrder = purchaseOrderMapper.toDto(updatedRecord, updatedLineItems);
        
        // Publish updated PO to vector store (contacts already fetched at the start)
        // Get assignedEmail from a project
        String assignedEmail = null;
        try {
            var company = companyRepository.findById(companyId);
            assignedEmail = company.getAssignedEmail();
        } catch (Exception e) {
            log.warn("Failed to fetch company assigned_email for vector ingestion after PO update: companyId={}", companyId, e);
        }
        
        vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, updatedPurchaseOrder.getProjectId(), 
            updatedPurchaseOrder, assignedEmail, vendorContact, shipToContact);
        
        // Publish integration push event if there's an active connection
        publishIntegrationPushEventIfNeeded(companyId, updatedPurchaseOrder, authorId);
        
        return updatedPurchaseOrder;
    }

    @Override
    public PurchaseOrder updatePurchaseOrderStatus(Long companyId, String id, PurchaseOrderStatusUpdate purchaseOrderStatusUpdate, String authorId) {
        PurchaseOrder purchaseOrder = this.getPurchaseOrder(companyId, id);
        
        // Extract contacts from the stored JSONB objects (needed for vector store ingestion)
        Contact vendorContact = purchaseOrder.getVendorContact();
        Contact shipToContact = purchaseOrder.getShipToContact();
        
        com.tosspaper.generated.model.PurchaseOrderStatus currentStatus = purchaseOrder.getStatus();
        com.tosspaper.generated.model.PurchaseOrderStatus newStatus = purchaseOrderStatusUpdate.getStatus();

        if (currentStatus == newStatus) {
            return purchaseOrder;
        }

        boolean isValidTransition = switch (currentStatus) {
            case PENDING, OPEN -> newStatus == com.tosspaper.generated.model.PurchaseOrderStatus.IN_PROGRESS || newStatus == com.tosspaper.generated.model.PurchaseOrderStatus.CANCELLED;
            case IN_PROGRESS -> newStatus == com.tosspaper.generated.model.PurchaseOrderStatus.COMPLETED || newStatus == com.tosspaper.generated.model.PurchaseOrderStatus.CLOSED;
            case CANCELLED -> newStatus == com.tosspaper.generated.model.PurchaseOrderStatus.PENDING;
            case COMPLETED, CLOSED -> false;
        };

        if (!isValidTransition) {
            throw new BadRequestException("illegal_state_transition",
                    String.format(ApiErrorMessages.PURCHASE_ORDER_ILLEGAL_STATE_TRANSITION, currentStatus.getValue(), newStatus.getValue())
            );
        }

        var changeLogEntry = new ChangeLogEntry(
                OffsetDateTime.now(),
                authorId,
                "STATUS_CHANGE",
                purchaseOrder.getStatus().getValue(),
                purchaseOrderStatusUpdate.getStatus().getValue(),
                purchaseOrderStatusUpdate.getNotes()
        );

        var updatedRecord = purchaseOrderRepository.updateStatus(id, purchaseOrderStatusUpdate.getStatus().getValue(), changeLogEntry);
        
        // Convert updated record to DTO, reusing items from the existing purchaseOrder
        PurchaseOrder updatedPurchaseOrder = purchaseOrderMapper.toDto(updatedRecord, purchaseOrder.getItems() != null 
                ? purchaseOrderMapper.toItemsPojos(purchaseOrder.getItems()) 
                : Collections.emptyList());

        String assignedEmail = null;
        try {
            var company = companyRepository.findById(companyId);
            assignedEmail = company.getAssignedEmail();
        } catch (Exception e) {
            log.warn("Failed to fetch company assigned_email for vector ingestion after status update: companyId={}", companyId, e);
        }
        
        vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, updatedPurchaseOrder.getProjectId(), 
            updatedPurchaseOrder, assignedEmail, vendorContact, shipToContact);
        
        // Publish integration push event if there's an active connection
        // Use the updatedPurchaseOrder DTO, which includes items
        publishIntegrationPushEventIfNeeded(companyId, updatedPurchaseOrder, authorId);
        
        return updatedPurchaseOrder;
    }
    
    /**
     * Validate that the total price of all items is not zero.
     * Total price is calculated as: unitPrice * quantity for each item.
     * Items without both unitPrice and quantity contribute 0 to the total.
     */
    private void validateTotalPrice(List<com.tosspaper.generated.model.PurchaseOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return; // Empty items list is valid
        }

        BigDecimal total = BigDecimal.ZERO;
        for (com.tosspaper.generated.model.PurchaseOrderItem item : items) {
            BigDecimal itemTotal = BigDecimal.ZERO;
            if (item.getUnitPrice() != null && item.getQuantity() != null) {
                itemTotal = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            }
            // If item is missing unitPrice or quantity, itemTotal remains 0
            total = total.add(itemTotal);
        }

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            throw new BadRequestException(
                    "invalid_total_price",
                    "Total price of all items must not be zero"
            );
        }
    }

    /**
     * Validate vendor currency matches the company default currency when multicurrency is disabled.
     * Only validates if there's an active FINANCIAL category connection.
     */
    private void validateVendorCurrency(Long companyId, Contact vendorContact) {

        // Find an active ACCOUNTING category connection
        Optional<IntegrationConnection> activeAccountingConnection = integrationConnectionService.findActiveByCompanyAndCategory(
                companyId, IntegrationCategory.ACCOUNTING);
        
        if (activeAccountingConnection.isEmpty()) {
            log.warn("No active ACCOUNTING connection for company {}, skipping vendor currency validation", companyId);
            return; // No active ACCOUNTING connection, skip validation
        }
        
        IntegrationConnection connection = activeAccountingConnection.get();

        // Get the vendor contact to check currency (validates company ownership)
        Contact vendorContactDto = contactService.getContactById(companyId, vendorContact.getId());
        String vendorCurrencyCode = vendorContactDto.getCurrencyCode();
        if (vendorCurrencyCode == null || vendorCurrencyCode.isBlank()) {
            log.warn("No vendor currency set for contact {}, skipping vendor currency validation", vendorContact.getId());
            return; // No vendor currency set, skip validation
        }

        // Check if multicurrency is disabled
        if (Boolean.TRUE.equals(connection.getMulticurrencyEnabled())) {
            log.warn("Multicurrency is enabled for company {}, skipping vendor currency validation", companyId);
            return;
        }

        String defaultCurrencyCode = connection.getDefaultCurrency().getCode();
        if (!vendorCurrencyCode.equals(defaultCurrencyCode)) {
            String providerName = connection.getProvider().getDisplayName();
            throw new BadRequestException(
                    "vendor_currency_mismatch",
                    String.format(
                            "Vendor currency '%s' does not match company default currency '%s'. Please enable multicurrency in %s to use different currencies.",
                            vendorCurrencyCode,
                            defaultCurrencyCode,
                            providerName
                    )
            );
        }
    }

    /**
     * Publish integration push event for purchase order if there are active integration connections.
     * Publishes to all active connections for the company.
     */
    private void publishIntegrationPushEventIfNeeded(Long companyId, PurchaseOrder purchaseOrder, String updatedBy) {
        try {
            log.info("Checking for active integration connections for company {} to publish purchase order push event", companyId);
            
            // Get all active connections for the company
            List<IntegrationConnection> connections = integrationConnectionService.listByCompany(companyId)
                    .stream()
                    .filter(conn -> conn.getStatus() == com.tosspaper.models.domain.integration.IntegrationConnectionStatus.ENABLED)
                    .toList();
            
            if (connections.isEmpty()) {
                log.info("No active integration connections for company {}, skipping integration push", companyId);
                return;
            }
            
            log.info("Found {} active integration connection(s) for company {}, publishing purchase order push event", 
                    connections.size(), companyId);
            
            // Serialize PurchaseOrder to JSON payload once
            String payload = objectMapper.writeValueAsString(purchaseOrder);
            
            // Publish event for each active connection
            for (IntegrationConnection connection : connections) {
                try {
                    IntegrationPushEvent event = new IntegrationPushEvent(
                            connection.getProvider(),
                            IntegrationEntityType.PURCHASE_ORDER,
                            companyId,
                            connection.getId(),
                            payload,
                            updatedBy
                    );
                    
                    integrationPushStreamPublisher.publish(event);
                    log.info("Published purchase order push event: id={}, displayId={}, provider={}", 
                            purchaseOrder.getId(), purchaseOrder.getDisplayId(), connection.getProvider());
                } catch (Exception e) {
                    log.error("Failed to publish integration push event for purchase order: id={}, provider={}", 
                            purchaseOrder.getId(), connection.getProvider(), e);
                    // Continue with other connections
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to publish integration push event for purchase order: id={}", purchaseOrder.getId(), e);
            // Don't throw - we don't want to fail purchase order operations if push fails
        }
    }
    
    /**
     * Get current user email from a security context.
     * Returns null if not available (e.g., in async contexts).
     */
    private String getCurrentUserEmail() {
        try {
            return com.tosspaper.common.security.SecurityUtils.getSubjectFromJwt();
        } catch (Exception e) {
            log.debug("Could not get current user email from security context", e);
            return null;
        }
    }
}