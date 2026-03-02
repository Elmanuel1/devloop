# Test Structure

## BDD Formatting: Proper Nesting

**CRITICAL:** BDD blocks (`given:`, `when:`, `then:`, `and:`) are **parent blocks**. All statements within them **MUST be indented** to show they belong to that block.

```groovy
// ✅ CORRECT: Statements nested under BDD blocks
def "test name"() {
    given: "description"
        // All statements indented here
        def variable = value
        
    and: "additional setup"
        // More indented statements
        
    when: "action"
        // Action statements indented
        
    then: "verification"
        // Assertions indented
        condition == true
        
    and: "more verification"
        // More assertions indented
        anotherCondition == true
}

// ❌ WRONG: Statements not indented
def "test name"() {
    given: "description"
    def variable = value  // Not indented - WRONG!
    
    when: "action"
    def result = action()  // Not indented - WRONG!
    
    then: "verification"
    result == expected  // Not indented - WRONG!
}
```

---

## Given-When-Then with Proper Indentation

```groovy
def "should create contact with all fields"() {
    given: "a valid contact request"
        def request = new ContactCreateRequest(name: "John Doe", email: "john@example.com")
        def savedEntity = new Contact(id: 1L, name: "John Doe", email: "john@example.com")

    when: "creating the contact"
        def result = service.createContact(request)

    then: "repository saves the entity"
        1 * repository.save(_) >> savedEntity

    and: "all fields are returned correctly"
        with(result) {
            id == 1L
            name == "John Doe"
        }
}
```

---

## Assert ALL Fields with `with()` Block

```groovy
then: "all fields are populated correctly"
    with(result) {
        id == expectedId
        name == "Expected Name"
        email == "expected@email.com"
        status == ContactStatus.ACTIVE
        companyId == 1L
        createdAt != null
        updatedAt != null
    }
```

**Why:** Incomplete assertions hide bugs. Every field must be verified.

---

## Verify Mock Interactions

```groovy
then: "repository is called once with correct ID"
    1 * repository.findById(contactId) >> Optional.of(entity)

and: "notification is NOT sent for read operations"
    0 * notificationService._
```

**Interaction patterns:**
- `1 * mock.method(arg)` — called exactly once
- `0 * mock._` — never called
- `_ * mock.method(_)` — any number of calls
- `1 * mock.method(_ as Type)` — type matching

---

## Test Exceptions from Dependencies

```groovy
def "should throw NotFoundException when contact not found"() {
    given: "a non-existent contact ID"
        def contactId = 999L

    when: "fetching the contact"
        service.getContact(contactId)

    then: "repository returns empty"
        1 * repository.findById(contactId) >> Optional.empty()

    and: "NotFoundException is thrown with ID in message"
        def ex = thrown(NotFoundException)
        ex.message.contains("999")
        ex.message.contains("Contact")
}
```

---

## NO Validation Tests in Unit Tests

```groovy
// ❌ WRONG — Don't test null/validation in services
def "should throw when name is null"() { ... }

// ✅ RIGHT — Assume inputs are already validated by API layer
def "should create contact with valid data"() {
    given: "a valid request (already validated by API layer)"
        def request = new ContactRequest(name: "John", email: "john@test.com")
    // ...
}
```

