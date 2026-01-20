package com.tosspaper.aiengine.streams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tosspaper.models.messaging.MessageHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream listener for vector store ingestion.
 * Allows external services to add documents to the vector store.
 */
@Slf4j
@Component("vectorStoreIngestionListener")
@RequiredArgsConstructor
public class VectorStoreIngestionHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "vector-store-ingestion";

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        processIngestion(message);
    }

    private void processIngestion(Map<String, String> messageData) {
        String content = messageData.get("content");
        String metadataJson = messageData.get("metadata");
        
        log.info("Received vector store ingestion request");
        
        try {
            // Validate inputs
            if (content == null || content.trim().isEmpty()) {
                log.error("Missing required field: content");
                return;
            }
            
            // Parse metadata JSON
            Map<String, Object> metadata = new HashMap<>();
            if (metadataJson != null && !metadataJson.trim().isEmpty()) {
                try {
                    metadata = objectMapper.readValue(metadataJson, 
                        new TypeReference<Map<String, Object>>() {});
                    log.debug("Parsed metadata: {}", metadata);
                } catch (Exception e) {
                    log.error("Failed to parse metadata JSON: {}", metadataJson, e);
                    // Continue with empty metadata rather than failing
                }
            }
            
            // Check if this is a Purchase Order (type="po") - if so, deconstruct into multiple documents
            if ("po".equals(metadata.get("type"))) {
                handlePurchaseOrderDeconstruction(content, metadata);
            } else {
                // Regular document - store as single document
                storeDocument(content, metadata);
            }
            
        } catch (Exception e) {
            log.error("Failed to store document in vector store", e);
            throw new RuntimeException("Vector store ingestion failed", e);
        }
    }
    
    /**
     * Handles deconstruction of a Purchase Order document into multiple vector store documents.
     * Extracts PO info, vendor contact, ship-to contact, and individual line items,
     * storing each as a separate document with appropriate metadata.
     */
    @SuppressWarnings("unchecked")
    private void handlePurchaseOrderDeconstruction(String content, Map<String, Object> baseMetadata) {
        String purchaseOrderId = (String) baseMetadata.get("purchaseOrderId");
        try {
            Map<String, Object> poData = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            List<Document> documentsToStore = new ArrayList<>();
            
            // 1. PO Info Document
            if (poData.containsKey("purchaseOrder")) {
                String poInfoDocId = generateDocumentId(purchaseOrderId, "po_info");
                String poInfoContent = formatPurchaseOrderInfo(poData.get("purchaseOrder"));
                Map<String, Object> poInfoMetadata = new HashMap<>(baseMetadata);
                poInfoMetadata.put("partType", "po_info");
                documentsToStore.add(new Document(poInfoDocId, poInfoContent, poInfoMetadata));
            }
            
            // 2. Vendor Contact Document
            if (poData.containsKey("vendorContact")) {
                String vendorDocId = generateDocumentId(purchaseOrderId, "vendor_contact");
                String vendorContent = formatContactInfo(poData.get("vendorContact"), "Vendor");
                Map<String, Object> vendorMetadata = new HashMap<>(baseMetadata);
                vendorMetadata.put("partType", "vendor_contact");
                documentsToStore.add(new Document(vendorDocId, vendorContent, vendorMetadata));
            }
            
            // 3. Ship-To Contact Document
            if (poData.containsKey("shipToContact")) {
                String shipToDocId = generateDocumentId(purchaseOrderId, "ship_to_contact");
                String shipToContent = formatContactInfo(poData.get("shipToContact"), "Ship To");
                Map<String, Object> shipToMetadata = new HashMap<>(baseMetadata);
                shipToMetadata.put("partType", "ship_to_contact");
                documentsToStore.add(new Document(shipToDocId, shipToContent, shipToMetadata));
            }
            
            // 4. Line Item Documents
            if (poData.containsKey("purchaseOrder")) {
                Map<String, Object> po = (Map<String, Object>) poData.get("purchaseOrder");
                if (po.containsKey("items")) {
                    List<?> items = (List<?>) po.get("items");
                    for (int i = 0; i < items.size(); i++) {
                        String lineItemDocId = generateDocumentId(purchaseOrderId, "line_item_" + i);
                        String lineItemContent = formatLineItemInfo(items.get(i), i);
                        Map<String, Object> lineItemMetadata = new HashMap<>(baseMetadata);
                        lineItemMetadata.put("partType", "line_item");
                        lineItemMetadata.put("itemIndex", i);
                        
                        // Validate that itemIndex is set before storing
                        if (!lineItemMetadata.containsKey("itemIndex") || lineItemMetadata.get("itemIndex") == null) {
                            log.error("CRITICAL: itemIndex is missing for line item {} in PO {}", i, purchaseOrderId);
                            throw new IllegalStateException("itemIndex must be set for all line items");
                        }
                        
                        documentsToStore.add(new Document(lineItemDocId, lineItemContent, lineItemMetadata));
                        log.debug("Created line item document: poId={}, itemIndex={}, metadata={}", 
                            purchaseOrderId, i, lineItemMetadata);
                    }
                }
            }
            
            // Store all deconstructed documents
            // Validate all line items have itemIndex before storing
            long lineItemCount = documentsToStore.stream()
                .filter(doc -> "line_item".equals(doc.getMetadata().get("partType")))
                .count();
            long lineItemsWithIndex = documentsToStore.stream()
                .filter(doc -> "line_item".equals(doc.getMetadata().get("partType")))
                .filter(doc -> doc.getMetadata().containsKey("itemIndex") && doc.getMetadata().get("itemIndex") != null)
                .count();
            
            if (lineItemCount > 0 && lineItemsWithIndex != lineItemCount) {
                log.error("CRITICAL: {} out of {} line items missing itemIndex metadata for PO {}", 
                    lineItemCount - lineItemsWithIndex, lineItemCount, purchaseOrderId);
                // Log details for debugging
                documentsToStore.stream()
                    .filter(doc -> "line_item".equals(doc.getMetadata().get("partType")))
                    .filter(doc -> !doc.getMetadata().containsKey("itemIndex") || doc.getMetadata().get("itemIndex") == null)
                    .forEach(doc -> log.error("Line item document missing itemIndex: metadata={}", doc.getMetadata()));
            }
            
            vectorStore.add(documentsToStore);
            log.info("Successfully deconstructed and stored purchase order in vector store: purchaseOrderId={}, documents={} (line items: {})", 
                purchaseOrderId, documentsToStore.size(), lineItemCount);
            
        } catch (Exception e) {
            log.error("Failed to deconstruct purchase order: purchaseOrderId={}", purchaseOrderId, e);
            throw new RuntimeException("PO deconstruction failed for purchaseOrderId: " + purchaseOrderId, e);
        }
    }
    
    /**
     * Stores a single document in the vector store.
     * Generates a deterministic UUID based on metadata type and ID.
     */
    private void storeDocument(String content, Map<String, Object> metadata) {
        try {
            // Generate UUID based on metadata (for non-PO documents)
            String documentId = UUID.randomUUID().toString();
            
            // Create and store document
            Document document = new Document(documentId, content, metadata);
            vectorStore.add(List.of(document));
            
            log.info("Successfully stored document in vector store (content size: {} chars, metadata fields: {})", 
                content.length(), metadata.size());
        } catch (Exception e) {
            log.error("Failed to store document in vector store", e);
            throw new RuntimeException("Vector store ingestion failed", e);
        }
    }
    
    /**
     * Formats purchase order information as key-value pairs.
     */
    @SuppressWarnings("unchecked")
    private String formatPurchaseOrderInfo(Object poObj) {
        StringBuilder sb = new StringBuilder();
        if (poObj instanceof Map) {
            Map<String, Object> po = (Map<String, Object>) poObj;
            sb.append("Purchase Order ID: ").append(nvl(po.get("id"))).append("\n");
            sb.append("Display ID: ").append(nvl(po.get("displayId"))).append("\n");
            if (po.get("description") != null) {
                sb.append("Description: ").append(po.get("description")).append("\n");
            }
            sb.append("Status: ").append(nvl(po.get("status"))).append("\n");
            if (po.get("createdAt") != null) {
                sb.append("Created At: ").append(po.get("createdAt")).append("\n");
            }
            if (po.get("updatedAt") != null) {
                sb.append("Updated At: ").append(po.get("updatedAt")).append("\n");
            }
        }
        return sb.toString().trim();
    }
    
    /**
     * Formats contact information as key-value pairs.
     */
    @SuppressWarnings("unchecked")
    private String formatContactInfo(Object contactObj, String role) {
        StringBuilder sb = new StringBuilder();
        if (contactObj instanceof Map) {
            Map<String, Object> contact = (Map<String, Object>) contactObj;
            sb.append(role).append(" Name: ").append(nvl(contact.get("name"))).append("\n");
            if (contact.get("email") != null) {
                sb.append("Email: ").append(contact.get("email")).append("\n");
            }
            if (contact.get("phone") != null) {
                sb.append("Phone: ").append(contact.get("phone")).append("\n");
            }
            if (contact.get("notes") != null) {
                sb.append("Notes: ").append(contact.get("notes")).append("\n");
            }
            
            // Address fields (OpenAPI uses snake_case field names)
            if (contact.get("address") instanceof Map) {
                Map<String, Object> address = (Map<String, Object>) contact.get("address");
                // "address" field contains the street address
                if (address.get("address") != null) {
                    sb.append("Street: ").append(address.get("address")).append("\n");
                }
                if (address.get("city") != null) {
                    sb.append("City: ").append(address.get("city")).append("\n");
                }
                // OpenAPI uses snake_case: state_or_province, postal_code
                if (address.get("state_or_province") != null) {
                    sb.append("State/Province: ").append(address.get("state_or_province")).append("\n");
                }
                if (address.get("postal_code") != null) {
                    sb.append("Postal Code: ").append(address.get("postal_code")).append("\n");
                }
                if (address.get("country") != null) {
                    sb.append("Country: ").append(address.get("country")).append("\n");
                }
                if (address.get("country_iso") != null) {
                    sb.append("Country ISO: ").append(address.get("country_iso")).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
    
    /**
     * Formats line item information as key-value pairs.
     */
    @SuppressWarnings("unchecked")
    private String formatLineItemInfo(Object itemObj, int itemIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("Item Index: ").append(itemIndex).append("\n");
        
        if (itemObj instanceof Map) {
            Map<String, Object> item = (Map<String, Object>) itemObj;
            if (item.get("name") != null) {
                sb.append("Item Name: ").append(item.get("name")).append("\n");
            }
            if (item.get("description") != null) {
                sb.append("Description: ").append(item.get("description")).append("\n");
            }
            if (item.get("quantity") != null) {
                sb.append("Quantity: ").append(item.get("quantity")).append("\n");
            }
            if (item.get("unitPrice") != null) {
                sb.append("Unit Price: ").append(item.get("unitPrice")).append("\n");
            }
            if (item.get("totalPrice") != null) {
                sb.append("Total Price: ").append(item.get("totalPrice")).append("\n");
            }
            if (item.get("notes") != null) {
                sb.append("Notes: ").append(item.get("notes")).append("\n");
            }
        }
        return sb.toString().trim();
    }
    
    /**
     * Generates deterministic UUID for deconstructed documents.
     */
    private String generateDocumentId(String purchaseOrderId, String partType) {
        String key = "purchase_order:" + purchaseOrderId + ":" + partType;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        }
    
    /**
     * Null-safe string converter.
     */
    private String nvl(Object value) {
        return value != null ? value.toString() : "";
    }
}

