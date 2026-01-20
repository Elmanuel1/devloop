# JaCoCo Coverage Analysis Reference

## Quick Commands to Find Uncovered Code

### Find All Uncovered Lines (nc class)
```bash
grep -n 'class="nc"' libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.{package}/{Class}.java.html
```

### Find Partially Covered Branches (pc bpc class)
```bash
grep -n 'class="pc bpc"' libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.{package}/{Class}.java.html
```

### Find All Branch Coverage Issues
```bash
grep -n 'branches missed' libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.{package}/{Class}.java.html
```

## CSS Class Reference

| Class Pattern | Color | Meaning |
|--------------|-------|---------|
| `fc` | Green | Fully covered |
| `fc bfc` | Green | Fully covered, all branches covered |
| `pc` | Yellow | Partially covered |
| `pc bpc` | Yellow | Partially covered, some branches missed |
| `nc` | Red | Not covered at all |
| `nc bnc` | Red | Not covered, branch not covered |

## Reading the HTML

### Line Format
```html
<span class="pc bpc" id="L61" title="1 of 2 branches missed.">if (condition) {</span>
```

- `id="L61"` → Line 61 in source file
- `title="1 of 2 branches missed."` → One branch untested
- The code inside the span is what needs testing

### Common Patterns to Test

| Pattern | What's Missing | Test Needed |
|---------|---------------|-------------|
| `if (x != null)` 1/2 missed | null case | Pass `null` for x |
| `if (list.isEmpty())` 1/2 missed | empty case | Pass empty list |
| `switch` with nc cases | unhandled case | Test each case value |
| `catch` block nc | exception path | Force exception to be thrown |
| `condition1 && condition2` 1/4 missed | edge case | Test false && true, etc. |

## Example Workflow

1. **Run tests with coverage**:
   ```bash
   ./gradlew :libs:api-tosspaper:test :libs:api-tosspaper:jacocoTestReport
   ```

2. **Find uncovered code in a class**:
   ```bash
   grep -E 'class="(nc|pc bpc)"' libs/api-tosspaper/build/reports/jacoco/test/html/com.tosspaper.purchaseorder/PurchaseOrderServiceImpl.java.html | head -20
   ```

3. **Create todo list of missing branches**

4. **Write targeted tests for each**

## Priority Order

1. **Methods with 0% coverage** (all `nc`) - Most impact
2. **Error handling paths** (catch blocks, exceptions)
3. **Null/empty checks** (common `1 of 2 missed`)
4. **Switch statement cases**
5. **Complex conditionals** (`&&`, `||` with missed branches)