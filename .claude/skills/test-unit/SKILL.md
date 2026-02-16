---
name: test-unit
description: Generate unit tests for service classes using Spock mocks. Use when creating service tests, business logic tests, or testing classes with injected dependencies. NO validation testing - trust API layer.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
hooks:
  PostToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: "bash $CLAUDE_PROJECT_DIR/scripts/run-tests.sh"
          once: true
---

# Unit Test Generation

Generate unit tests for service classes using Spock framework with mocks.

## MANDATORY: Analyze JaCoCo Coverage First

Before writing ANY tests, you MUST analyze the JaCoCo source coverage report to identify uncovered branches.

### Step 1: Locate the Coverage Report

```
libs/{module}/build/reports/jacoco/test/html/{package}/{ClassName}.java.html
```

Example: `libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.purchaseorder/PurchaseOrderServiceImpl.java.html`

### Step 2: Identify Uncovered Code

Parse the HTML for these CSS class markers:

| Class | Meaning | Action |
|-------|---------|--------|
| `nc` | Not covered (red) | MUST write test |
| `pc bpc` | Partially covered branch | MUST test missing branch |
| `nc bnc` | Branch not covered | MUST test this branch |
| `fc` | Fully covered (green) | Already tested |

### Step 3: Extract Missing Branches

Look for `title` attributes that show branch coverage:
- `title="1 of 2 branches missed."` → Need test for the other branch
- `title="All 2 branches missed."` → Need tests for both branches

Example from JaCoCo HTML:
```html
<span class="pc bpc" id="L61" title="1 of 2 branches missed.">if (allOrders.size() > 1) {</span>
<span class="nc" id="L62">    log.error("Multiple purchase orders found...");</span>
```
This means: Line 61's `if (allOrders.size() > 1)` has 1 of 2 branches untested. The `true` branch (line 62) is never executed.

### Step 4: Create Targeted Tests

For each uncovered branch, write a specific test:
```groovy
def "should throw ServiceException when multiple POs found with same ID"() {
    given: "repository returns multiple records with same ID"
        purchaseOrderRepository.findById("po-1") >> [record1, record2]

    when: "getting purchase order"
        service.getPurchaseOrder(1L, "po-1")

    then: "throws ServiceException"
        thrown(ServiceException)
}
```

## WARNINGS

- **DO NOT** write unit tests for REST controllers or repositories — those MUST be integration tests (see `test-integration` skill)
- **DO NOT** use deprecated `statusCodeValue` — use `response.statusCode == HttpStatus.OK` instead

## Scope

✅ Service classes with injected dependencies
✅ Business logic and calculations
✅ Mapper classes
✅ Utility classes with dependencies

## Coverage Goal

**The primary goal is that ALL classes in the module have tests.** Every class must have a corresponding Spec file. Do not skip classes just because the module already hit 80%.

**Minimum 80% line coverage per module** — this is a hard floor.

### Approach
1. **First**: Identify ALL source classes in the module that lack test coverage
2. **Then**: Write tests for every untested class
3. **Finally**: Verify total module coverage is above 80%

## CRITICAL: No Superficial Tests

Tests MUST NOT be superficial. Every test must:

1. **Set ALL fields** on input objects — never use empty/default builders. Populate every field with realistic test data:
   ```groovy
   // WRONG - superficial, most fields empty
   def po = PurchaseOrder.builder().displayId("PO-1").build()

   // CORRECT - all fields populated
   def po = PurchaseOrder.builder()
       .displayId("PO-001")
       .vendorName("Acme Corp")
       .status(PurchaseOrderStatus.DRAFT)
       .totalAmount(new BigDecimal("1500.00"))
       .lineItems([lineItem1, lineItem2])
       .notes("Rush order")
       .build()
   ```

2. **Assert ALL fields** on output objects — verify every field in the result, not just one or two:
   ```groovy
   // WRONG - only checks one field
   then: "result is returned"
       result != null
       result.success

   // CORRECT - asserts all fields using with() block
   then: "result has all expected values"
       with(result) {
           success == true
           externalId == "qb-po-1"
           externalDocNumber == "PO-001"
           providerVersion == "0"
           providerLastUpdatedAt != null
           errorMessage == null
           conflictDetected == false
           retryable == false
       }
   ```

3. **Verify mock interactions with specific arguments** — not just `_` wildcards:
   ```groovy
   // WRONG - all wildcards
   1 * service.save(_, _)

   // CORRECT - verify actual values passed
   1 * service.save("conn-1", { it.displayId == "PO-001" && it.vendorName == "Acme Corp" })
   ```

## What to Test

### Primary Focus: Business Logic
- Calculations (totals, discounts, percentages)
- Conditional logic (if/else, switch, rules)
- Data transformations
- State transitions
- Algorithm correctness

### Secondary
- Happy path for all public methods
- Exception handling (when dependencies fail/return empty)
- All fields in returned objects using `with()` blocks
- Mock interaction verification
- **MANDATORY**: Test all logical branches (if/else, switch)
- **MANDATORY**: Test `NotFoundException` for ALL methods that retrieve/update by ID
- **MANDATORY**: Verify builder field names and types to avoid `MissingMethodException`

### What NOT to Test

❌ Null/empty/format validation (API layer handles this)
❌ Database operations (use integration tests)
❌ HTTP/REST behavior (use integration tests)

## Quick Reference

Detailed patterns and examples in:

1. **[JaCoCo Analysis](references/JACOCO.md)** — Commands to find uncovered code, CSS class reference, priority order
2. **[Mocking Patterns](references/MOCKING.md)** — Mock setup, static methods, ObjectMapper, value objects
3. **[Test Structure](references/STRUCTURE.md)** — Given-When-Then, `with()` blocks, interaction verification
4. **[Business Logic Examples](references/EXAMPLES.md)** — Calculations, conditionals, state transitions, transformations

## Utility Scripts

This skill shares utility scripts with the test-integration skill for running tests:

- `../test-integration/scripts/run-tests.sh` - Run all tests in the project
- `../test-integration/scripts/run-tests-coverage.sh` - Run tests with coverage report
- `../test-integration/scripts/run-specific-test.sh <TestClass>` - Run a single test class
- `../test-integration/scripts/check-coverage.sh` - Validate code coverage threshold

See [test-integration skill](../test-integration/) for detailed script documentation.

## File Structure

```
src/test/groovy/com/tosspaper/{package}/
├── {ServiceName}Spec.groovy
├── {MapperName}Spec.groovy
```

## Naming Convention

- Test class: `{ClassName}Spec.groovy`
- Test method: `"should {action} when {condition}"`

Examples:
- `"should create contact with all fields"`
- `"should throw NotFoundException when contact not found"`
- `"should apply discount when customer is VIP"`
