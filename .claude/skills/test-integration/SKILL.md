---
name: test-integration
description: Generate integration tests for repositories and controllers using Testcontainers. Use for database tests, API endpoint tests, and Jakarta Bean Validation tests. Read OpenAPI spec for validation constraints.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
  - Bash
hooks:
  PostToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: "bash $CLAUDE_PROJECT_DIR/scripts/run-tests.sh"
          once: true
---

# Integration Test Generation

Generate integration tests for repositories and controllers using Testcontainers PostgreSQL.

## MANDATORY: Analyze JaCoCo Coverage First

Before writing ANY tests, you MUST analyze the JaCoCo source coverage report to identify uncovered branches.

### Step 1: Locate the Coverage Report

```
libs/{module}/build/reports/jacoco/test/html/{package}/{ClassName}.java.html
```

Example: `libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.purchaseorder/PurchaseOrderRepositoryImpl.java.html`

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
<span class="pc bpc" id="L85" title="1 of 4 branches missed.">(page != null && pageSize > 0)</span>
```
This means: 1 of 4 branch conditions is untested. Write tests for edge cases like `page=null` or `pageSize=0`.

### Step 4: List All Uncovered Lines

Before writing tests, create a TODO list of all uncovered lines:
```
Uncovered branches in PurchaseOrderRepositoryImpl:
- Line 85: page != null && pageSize > 0 (1 of 4 missed)
- Line 128: items != null (1 of 2 missed)
- Lines 405-437: publishIntegrationPushEventIfNeeded (entire method)
```

### Step 5: Create Targeted Tests

For each uncovered branch, write a specific integration test that exercises that code path.

## Scope

✅ Repository classes (database operations)
✅ Controller classes (API endpoints)
✅ Jakarta Bean Validation (API layer only)

## Test Types Summary

| Type | What to Test | Validation? |
|------|--------------|-------------|
| Repository | CRUD, queries, DB constraints | ❌ NO |
| Controller | HTTP endpoints, responses | ✅ YES (from OpenAPI) |

## Quick Reference

Detailed patterns and examples in:

1. **[JaCoCo Analysis](references/JACOCO.md)** — Commands to find uncovered code, CSS class reference, priority order
2. **[Repository Tests](references/REPOSITORY.md)** — Setup, DSLContext, constraints, field persistence
3. **[Controller Tests](references/CONTROLLER.md)** — GET/POST/PUT/DELETE patterns, auth headers, CSRF
4. **[Validation Tests](references/VALIDATION.md)** — Jakarta Bean Validation, OpenAPI-to-test mapping
5. **[Setup Guide](references/SETUP.md)** — BaseIntegrationTest, cleanup patterns

## Utility Scripts

This skill includes helper scripts for running and managing integration tests:

- `scripts/run-tests.sh` - Run all tests in the project
  - Automatically detects changed files and runs relevant tests
- `scripts/run-tests-coverage.sh` - Run tests with JaCoCo coverage report
  - Fails if coverage is below threshold (default: 70%)
- `scripts/run-specific-test.sh <TestClass>` - Run a single test class
  - Example: `bash scripts/run-specific-test.sh ContactControllerSpec`
  - Useful for debugging specific test failures
- `scripts/check-coverage.sh` - Validate code coverage meets minimum threshold
  - Generates coverage report and shows detailed metrics

## Checking Test Reports

When tests fail or you need to verify test status without rerunning:

1. **Read the HTML report**: `{module}/build/reports/tests/test/index.html`
   - Shows summary: total tests, failures, success rate
   - Lists failed tests with links to detailed reports
   - Check the "Failed tests" tab for quick overview

2. **Read the XML report**: `{module}/build/test-results/test/TEST-{ClassName}Spec.xml`
   - Contains detailed failure messages and stack traces
   - Shows exact assertion failures with expected vs actual values
   - Use `grep` or `read_file` to find specific failures

3. **Check detailed HTML per class**: `{module}/build/reports/tests/test/classes/{package}/{ClassName}Spec.html`
   - Shows full test output, logs, and failure details
   - Useful for debugging specific test failures

**Workflow:**
- When user reports test failures, check the HTML report first for summary
- Read XML reports for specific failure details
- Fix issues based on failure messages
- Re-run tests only after fixes are complete

## Mandatory Negative Tests

For every controller endpoint, you MUST test:
- **404 Not Found**: Resource doesn't exist or belongs to another company
- **403 Forbidden**: Insufficient permissions or multi-tenant violation
- **400 Bad Request**: Validation errors, missing required headers

## File Structure

```
src/test/groovy/com/tosspaper/{package}/
├── {RepositoryName}Spec.groovy           # Repository integration tests
├── {ControllerName}Spec.groovy           # Controller happy path tests
├── {ControllerName}ValidationSpec.groovy # Controller validation tests
```

## Naming Convention

- Test class: `{ClassName}Spec.groovy`
- Repository: `"should {action} when {condition}"`
- Controller: `"{METHOD} /path returns {status} when {condition}"`

Examples:
- `"should find contacts by company ID"`
- `"GET /v1/contacts returns 200 with all contacts"`
- `"POST /v1/contacts returns 400 when name is missing"`
