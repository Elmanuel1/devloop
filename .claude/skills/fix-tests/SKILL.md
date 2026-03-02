
---
name: fix-tests
description: Fix failing tests by analyzing test reports and fixing all errors before re-running. Use when tests fail and you need to fix all errors systematically.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
  - Bash
  - TodoWrite
---

# Fix Tests Skill

Fix failing tests by analyzing test reports and fixing all errors before re-running.

## Invocation

Use `/fix-tests` or `/fix-tests <module>` to fix failing tests.

Examples:
- `/fix-tests` - Fix all failing tests across all modules
- `/fix-tests api-tosspaper` - Fix tests in specific module
- `/fix-tests email-engine` - Fix tests in email-engine module

## Workflow

### Step 1: Find Test Reports

Look for test reports in these locations:
- `libs/{module}/build/reports/tests/test/index.html`
- `services/{module}/build/reports/tests/test/index.html`

### Step 2: Parse Failures

Extract all test failures from the HTML report:
1. Read the index.html file
2. Find failed test classes and methods
3. Get the error messages and stack traces

### Step 3: Create Todo List

Create a todo list with ALL failures before fixing any:
- Each failing test = one todo item
- Include: test class, method name, error summary
- Mark as pending

### Step 4: Fix All Errors

For each failure:
1. Mark todo as in_progress
2. Read the test file
3. Read the implementation being tested
4. Fix the issue (test or implementation)
5. Mark todo as completed
6. Move to next failure

**IMPORTANT: Fix ALL errors before running tests again to avoid back-and-forth.**

### Step 5: Verify Fixes

Only after ALL todos are completed:
1. Run the tests once: `./gradlew :libs:{module}:test`
2. If new failures, go back to Step 2
3. If all pass, report success

## Parsing Test Reports

Use this approach to extract failures from HTML:

```bash
# Find all failed test links in the report
grep -o 'class="failures".*href="[^"]*"' index.html
```

Or read the HTML and look for:
- `<div class="failures">` sections
- `<a href="classes/...">` links to failed classes
- Error messages in the detailed class reports

## Example Todo List

```
1. [pending] PurchaseOrderControllerSpec - "should return 404 when PO not found"
2. [pending] EmailProcessorTest - "should handle invalid attachment"
3. [pending] S3StorageServiceTest - "should throw on missing bucket"
```

## Notes

- Always read the full stack trace to understand the root cause
- Check if the test expectation is wrong vs implementation is wrong
- Update mocks if dependencies changed
- Don't run tests until ALL known failures are addressed