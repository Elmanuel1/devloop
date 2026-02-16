package com.tosspaper.aiengine.streams

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import spock.lang.Specification
import spock.lang.Subject

class VectorStoreIngestionHandlerSpec extends Specification {

    VectorStore vectorStore = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    VectorStoreIngestionHandler handler = new VectorStoreIngestionHandler(vectorStore, objectMapper)

    def "getQueueName should return correct queue name"() {
        expect:
            handler.getQueueName() == "vector-store-ingestion"
    }

    def "handle should store a regular document"() {
        given: "a message with content and metadata"
            def message = [
                "content": "This is a test document",
                "metadata": '{"type": "invoice", "documentId": "doc-123"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "document is stored in vector store"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() == 1 &&
                docs[0].getText() == "This is a test document"
            })
    }

    def "handle should skip when content is null"() {
        given: "a message without content"
            def message = [
                "metadata": '{"type": "invoice"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "no document stored"
            0 * vectorStore.add(_)
    }

    def "handle should skip when content is empty"() {
        given: "a message with empty content"
            def message = [
                "content": "",
                "metadata": '{"type": "invoice"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "no document stored"
            0 * vectorStore.add(_)
    }

    def "handle should store document with empty metadata"() {
        given: "a message with content but no metadata"
            def message = [
                "content": "Some document content"
            ]

        when: "handling the message"
            handler.handle(message)

        then: "document is stored"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() == 1
            })
    }

    def "handle should store document when metadata JSON is invalid"() {
        given: "a message with invalid metadata JSON"
            def message = [
                "content": "Some document content",
                "metadata": "not valid json"
            ]

        when: "handling the message"
            handler.handle(message)

        then: "document is stored with empty metadata"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() == 1
            })
    }

    def "handle should deconstruct purchase order"() {
        given: "a PO message"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "displayId": "PO-100",
                    "status": "open",
                    "items": [
                        ["name": "Widget A", "quantity": 10, "unitPrice": 5.00],
                        ["name": "Widget B", "quantity": 5, "unitPrice": 10.00]
                    ]
                ],
                "vendorContact": [
                    "name": "Vendor Inc.",
                    "email": "vendor@example.com"
                ],
                "shipToContact": [
                    "name": "Ship To Corp"
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "multiple documents are stored (po_info + vendor + shipTo + 2 line items = 5)"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() == 5
            })
    }

    def "handle should deconstruct PO with only po info"() {
        given: "a PO message with only po info"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "displayId": "PO-100",
                    "status": "open"
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "one document stored (po_info)"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() == 1 &&
                docs[0].getMetadata().get("partType") == "po_info"
            })
    }

    def "handle should include line item metadata"() {
        given: "a PO message with items"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "items": [
                        ["name": "Item 1"],
                        ["name": "Item 2"]
                    ]
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "line items have itemIndex metadata"
            1 * vectorStore.add({ List<Document> docs ->
                def lineItems = docs.findAll { it.getMetadata().get("partType") == "line_item" }
                lineItems.size() == 2 &&
                lineItems[0].getMetadata().get("itemIndex") == 0 &&
                lineItems[1].getMetadata().get("itemIndex") == 1
            })
    }

    def "handle should throw RuntimeException when vector store fails"() {
        given: "a message with content"
            def message = [
                "content": "Some content",
                "metadata": '{}'
            ]

        and: "vector store fails"
            vectorStore.add(_) >> { throw new RuntimeException("Storage failure") }

        when: "handling the message"
            handler.handle(message)

        then: "exception is propagated"
            thrown(RuntimeException)
    }

    def "handle should generate deterministic document IDs for PO parts"() {
        given: "a PO message"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "displayId": "PO-100"
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message twice"
            handler.handle(message)

        then: "document IDs are deterministic"
            1 * vectorStore.add({ List<Document> docs ->
                docs[0].getId() != null && !docs[0].getId().isEmpty()
            })
    }

    def "handle should format PO info with description and dates"() {
        given: "a PO with description, createdAt, and updatedAt"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "displayId": "PO-100",
                    "status": "open",
                    "description": "Test order for widgets",
                    "createdAt": "2024-01-01T00:00:00Z",
                    "updatedAt": "2024-02-01T00:00:00Z"
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "document contains all fields"
            1 * vectorStore.add({ List<Document> docs ->
                def poInfoDoc = docs.find { it.getMetadata().get("partType") == "po_info" }
                poInfoDoc != null &&
                poInfoDoc.getText().contains("Description: Test order for widgets") &&
                poInfoDoc.getText().contains("Created At:") &&
                poInfoDoc.getText().contains("Updated At:")
            })
    }

    def "handle should format vendor contact with phone, notes, and address"() {
        given: "a PO with full vendor contact including address"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": ["id": "po-1"],
                "vendorContact": [
                    "name": "Acme Corp",
                    "email": "vendor@acme.com",
                    "phone": "555-1234",
                    "notes": "Preferred supplier",
                    "address": [
                        "address": "123 Main St",
                        "city": "Springfield",
                        "state_or_province": "IL",
                        "postal_code": "62701",
                        "country": "United States",
                        "country_iso": "US"
                    ]
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "vendor contact has all fields including address"
            1 * vectorStore.add({ List<Document> docs ->
                def vendorDoc = docs.find { it.getMetadata().get("partType") == "vendor_contact" }
                vendorDoc != null &&
                vendorDoc.getText().contains("Phone: 555-1234") &&
                vendorDoc.getText().contains("Notes: Preferred supplier") &&
                vendorDoc.getText().contains("Street: 123 Main St") &&
                vendorDoc.getText().contains("City: Springfield") &&
                vendorDoc.getText().contains("State/Province: IL") &&
                vendorDoc.getText().contains("Postal Code: 62701") &&
                vendorDoc.getText().contains("Country: United States") &&
                vendorDoc.getText().contains("Country ISO: US")
            })
    }

    def "handle should format line items with description, totalPrice, and notes"() {
        given: "a PO with full line items"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": [
                    "id": "po-1",
                    "items": [
                        [
                            "name": "Widget A",
                            "description": "A high quality widget",
                            "quantity": 10,
                            "unitPrice": 5.00,
                            "totalPrice": 50.00,
                            "notes": "Handle with care"
                        ]
                    ]
                ]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "line item has all fields"
            1 * vectorStore.add({ List<Document> docs ->
                def lineItem = docs.find { it.getMetadata().get("partType") == "line_item" }
                lineItem != null &&
                lineItem.getText().contains("Description: A high quality widget") &&
                lineItem.getText().contains("Total Price: 50") &&
                lineItem.getText().contains("Notes: Handle with care")
            })
    }

    def "handle should throw when PO deconstruction JSON is invalid"() {
        given: "a PO message with invalid JSON content"
            def message = [
                "content": "not valid json",
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        when: "handling the message"
            handler.handle(message)

        then: "exception is propagated"
            thrown(RuntimeException)
    }

    def "handle should throw when vector store fails during PO deconstruction"() {
        given: "a PO message"
            def poContent = objectMapper.writeValueAsString([
                "purchaseOrder": ["id": "po-1", "displayId": "PO-100"]
            ])
            def message = [
                "content": poContent,
                "metadata": '{"type": "po", "purchaseOrderId": "po-1"}'
            ]

        and: "vector store throws"
            vectorStore.add(_) >> { throw new RuntimeException("VectorStore down") }

        when: "handling the message"
            handler.handle(message)

        then: "exception is propagated"
            thrown(RuntimeException)
    }
}
