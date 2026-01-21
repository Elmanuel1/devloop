package com.tosspaper.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.generated.model.Contact;
import com.tosspaper.generated.model.PurchaseOrder;
import com.tosspaper.models.messaging.MessagePublisher;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class VectorStoreIngestionPublisher {

    private static final String STREAM_NAME = "vector-store-ingestion";
    
    private final MessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a purchase order event to the Redis stream for vector store ingestion.
     * Publishes ONE message containing all PO information (as JSON with enriched data).
     * The stream listener will deconstruct this into multiple documents for storage.
     * Failures are logged but do not block the request.
     * 
     * @param companyId ID of the company
     * @param projectId ID of the project
     * @param purchaseOrder The full purchase order DTO with items
     * @param assignedEmail Email of the assigned account manager (nullable)
     * @param vendorContact Full vendor contact information (nullable)
     * @param shipToContact Full ship-to contact information (nullable)
     */
    public void publishPurchaseOrderEvent(Long companyId, String projectId, PurchaseOrder purchaseOrder, 
                                           String assignedEmail, Contact vendorContact, Contact shipToContact) {
        try {
            // Build enriched content with purchase order and full contact information
            // Listener will deconstruct this into multiple documents
            Map<String, Object> enrichedContent = new HashMap<>();
            enrichedContent.put("purchaseOrder", purchaseOrder);
            if (vendorContact != null) {
                enrichedContent.put("vendorContact", vendorContact);
            }
            if (shipToContact != null) {
                enrichedContent.put("shipToContact", shipToContact);
            }
            
            String content = objectMapper.writeValueAsString(enrichedContent);
            
            // Build compact metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("companyId", companyId);
            metadata.put("projectId", projectId);
            metadata.put("purchaseOrderId", purchaseOrder.getId());
            metadata.put("displayId", purchaseOrder.getDisplayId());
            metadata.put("type", "po");
            
            // Add vendor/supplier name for filtering
            if (vendorContact != null && vendorContact.getName() != null) {
                String normalizedName = vendorContact.getName().toLowerCase().trim();
                metadata.put("vendorName", normalizedName);
                metadata.put("supplierName", normalizedName); // Same as vendor for compatibility
            }
            
            // Add line items summary for filtering
            if (purchaseOrder.getItems() != null && !purchaseOrder.getItems().isEmpty()) {
                String lineItems = purchaseOrder.getItems().stream()
                    .map(item -> item.getName() != null ? item.getName().toLowerCase().trim() : "")
                    .filter(name -> !name.isEmpty())
                    .collect(java.util.stream.Collectors.joining(", "));
                if (!lineItems.isEmpty()) {
                    metadata.put("lineItems", lineItems);
                }
            }
            
            if (assignedEmail != null) {
                metadata.put("assignedEmail", assignedEmail);
            }
            
            String metadataJson = objectMapper.writeValueAsString(metadata);
            
            // Build stream record with two fields (listener generates IDs for deconstructed docs)
            Map<String, String> recordData = new HashMap<>();
            recordData.put("content", content);
            recordData.put("metadata", metadataJson);
            
            // Set baggage from available context (companyId) before publishing
            // This ensures baggage is propagated to the Redis stream
            io.opentelemetry.api.baggage.BaggageBuilder baggageBuilder = Baggage.builder();
            baggageBuilder.put("company-id", String.valueOf(companyId));
            if (projectId != null) {
                baggageBuilder.put("project-id", projectId);
            }
            Baggage baggage = baggageBuilder.build();
            Context contextWithBaggage = baggage.storeInContext(Context.current());
            
            try (Scope scope = contextWithBaggage.makeCurrent()) {
                // Publish to Redis stream (trace context and baggage will be automatically propagated)
                // Listener will deconstruct this single message into multiple documents
                messagePublisher.publish(STREAM_NAME, recordData);
                log.info("Published purchase order to vector store ingestion stream (will be deconstructed by listener): purchaseOrderId={}", purchaseOrder.getId());
            }
                
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize purchase order for vector ingestion: purchaseOrderId={}", purchaseOrder.getId(), e);
        } catch (Exception e) {
            log.error("Failed to publish purchase order to Redis stream: purchaseOrderId={}", purchaseOrder.getId(), e);
        }
    }
    
}

