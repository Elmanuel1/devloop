# Repository Tests

## What to Test

✅ CRUD operations (create, read, update, delete)
✅ Custom query methods
✅ Database constraints (unique indexes, foreign keys)
✅ Pagination and sorting
✅ All fields persisted correctly
✅ **MANDATORY**: Verify domain model field names and types before writing tests

## What NOT to Test

❌ Input validation (API layer handles this)
❌ Business logic (service layer handles this)
❌ Null checks (trust the service layer)

---

## Setup Pattern

```groovy
class ContactRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Shared
    Long companyId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
        dsl.insertInto(Tables.COMPANIES)
            .set([id: companyId, name: "Test Company", email: "test@test.com"])
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }
}
```

---

## BDD Formatting: Proper Nesting

**CRITICAL:** BDD blocks (`given:`, `when:`, `then:`, `and:`) are **parent blocks**. All statements within them **MUST be indented** to show they belong to that block.

```groovy
// ✅ CORRECT: Statements nested under BDD blocks
def "test name"() {
    given: "description"
        // All statements indented here
        def variable = value
        
    when: "action"
        // Action statements indented
        
    then: "verification"
        // Assertions indented
        condition == true
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

## Use DSLContext for Data Setup

```groovy
def "should find contacts by company"() {
    given: "contacts exist for the company"
        dsl.insertInto(Tables.CONTACTS)
            .set([id: 1L, company_id: companyId, name: "Contact 1", email: "c1@test.com"])
            .execute()
        dsl.insertInto(Tables.CONTACTS)
            .set([id: 2L, company_id: companyId, name: "Contact 2", email: "c2@test.com"])
            .execute()

    when: "querying by company ID"
        def results = repository.findByCompanyId(companyId)

    then: "all contacts returned with correct fields"
        results.size() == 2
        with(results[0]) {
            id == 1L
            name == "Contact 1"
            email == "c1@test.com"
        }
}
```

---

## Test Database Constraints

```groovy
def "should enforce unique constraint on email per company"() {
    given: "a contact exists"
        dsl.insertInto(Tables.CONTACTS)
            .set([company_id: companyId, name: "First", email: "duplicate@test.com"])
            .execute()

    when: "inserting another contact with same email"
        dsl.insertInto(Tables.CONTACTS)
            .set([company_id: companyId, name: "Second", email: "duplicate@test.com"])
            .execute()

    then: "database constraint violation"
        thrown(DataIntegrityViolationException)
}
```

---

## Test All Fields Persisted

```groovy
def "should save contact with all fields"() {
    given: "a complete contact"
        def contact = new Contact(
            companyId: companyId,
            name: "Full Contact",
            email: "full@test.com",
            phone: "+1-555-1234",
            type: ContactType.VENDOR,
            status: ContactStatus.ACTIVE
        )

    when: "saving the contact"
        def saved = repository.save(contact)

    then: "contact is persisted with all fields"
        saved.id != null
        with(saved) {
            companyId == companyId
            name == "Full Contact"
            email == "full@test.com"
            phone == "+1-555-1234"
            type == ContactType.VENDOR
            status == ContactStatus.ACTIVE
            createdAt != null
        }

    and: "can be retrieved with same fields"
        def retrieved = repository.findById(saved.id).get()
        with(retrieved) {
            id == saved.id
            name == "Full Contact"
            email == "full@test.com"
        }
}
```

