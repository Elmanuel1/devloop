# Business Logic Test Examples

## Calculations

```groovy
def "should calculate invoice total with tax and discount"() {
    given: "line items"
        def items = [
            new LineItem(quantity: 2, unitPrice: 50.00),
            new LineItem(quantity: 1, unitPrice: 100.00)
        ]
        def taxRate = 0.10
        def discountPercent = 0.05

    when: "calculating invoice"
        def result = service.calculateInvoice(items, taxRate, discountPercent)

    then: "all calculations are correct"
        with(result) {
            subtotal == 200.00           // (2*50) + (1*100)
            discountAmount == 10.00      // 200 * 0.05
            taxableAmount == 190.00      // 200 - 10
            taxAmount == 19.00           // 190 * 0.10
            total == 209.00              // 190 + 19
        }
}
```

---

## Conditional Logic

```groovy
def "should apply premium discount for VIP customers"() {
    given: "a VIP customer order"
        def order = new Order(customerId: 1L, total: 100.00)
        def customer = new Customer(id: 1L, tier: CustomerTier.VIP)

    when: "calculating final price"
        def result = service.calculateFinalPrice(order)

    then: "customer is fetched"
        1 * customerRepository.findById(1L) >> Optional.of(customer)

    and: "VIP discount (20%) is applied"
        result.discount == 20.00
        result.finalPrice == 80.00
}

def "should apply no discount for new customers"() {
    given: "a new customer order"
        def order = new Order(customerId: 3L, total: 100.00)
        def customer = new Customer(id: 3L, tier: CustomerTier.NEW)

    when: "calculating final price"
        def result = service.calculateFinalPrice(order)

    then: "customer is fetched"
        1 * customerRepository.findById(3L) >> Optional.of(customer)

    and: "no discount applied"
        result.discount == 0.00
        result.finalPrice == 100.00
}
```

---

## State Transitions

```groovy
def "should transition order from PENDING to APPROVED when all items in stock"() {
    given: "a pending order"
        def order = new Order(id: 1L, status: OrderStatus.PENDING, items: [item1, item2])

    and: "all items are in stock"
        inventoryService.checkStock(item1) >> true
        inventoryService.checkStock(item2) >> true

    when: "processing the order"
        def result = service.processOrder(order)

    then: "order transitions to APPROVED"
        result.status == OrderStatus.APPROVED
        result.approvedAt != null
}

def "should transition order to BACKORDERED when items out of stock"() {
    given: "a pending order"
        def order = new Order(id: 1L, status: OrderStatus.PENDING, items: [item1, item2])

    and: "one item is out of stock"
        inventoryService.checkStock(item1) >> true
        inventoryService.checkStock(item2) >> false

    when: "processing the order"
        def result = service.processOrder(order)

    then: "order transitions to BACKORDERED"
        result.status == OrderStatus.BACKORDERED
        result.backorderedItems == [item2]
}
```

---

## Business Rules

```groovy
def "should reject order when total exceeds credit limit"() {
    given: "a customer with credit limit"
        def customer = new Customer(id: 1L, creditLimit: 1000.00)
        def order = new Order(customerId: 1L, total: 1500.00)

    when: "placing the order"
        service.placeOrder(order)

    then: "customer is fetched"
        1 * customerRepository.findById(1L) >> Optional.of(customer)

    and: "order is rejected"
        def ex = thrown(CreditLimitExceededException)
        ex.message.contains("1500")
        ex.message.contains("1000")

    and: "order is NOT saved"
        0 * orderRepository.save(_)
}
```

---

## Collections

```groovy
def "should return all contacts for company"() {
    given: "a company ID"
        def companyId = 1L
        def entities = [
            new Contact(id: 1L, name: "First"),
            new Contact(id: 2L, name: "Second"),
            new Contact(id: 3L, name: "Third")
        ]

    when: "fetching contacts"
        def results = service.getContactsByCompany(companyId)

    then: "repository returns entities"
        1 * repository.findByCompanyId(companyId) >> entities

    and: "results contain all contacts with correct fields"
        results.size() == 3
        with(results[0]) { id == 1L; name == "First" }
        with(results[1]) { id == 2L; name == "Second" }
        with(results[2]) { id == 3L; name == "Third" }
}
```

