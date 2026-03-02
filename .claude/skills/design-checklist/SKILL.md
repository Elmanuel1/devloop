---
name: design-checklist
description: Architecture design checklist for planning features before writing code. Covers task splitting, design questions, error handling, edge cases, and the 28-point resilience gate. Read this before implementing any new feature or endpoint.
---

# Architecture Design Checklist

Read this **before writing any code** for a new feature, endpoint, or significant change.

---

## Step 1 — Assess the Full Scope

- What is the entire scope? List every endpoint, class, and feature involved.
- Split into independent features — self-contained work units that can run in parallel with **no overlap and no dependencies on each other**. Each feature = one PR.

### Task Splitting Rules

- **No overlap** — two features must never touch the same file. If they do, the shared part goes into a foundation PR.
- **No cross-dependencies** — Feature B must not depend on Feature A. If it does, merge them or extract the dependency into the foundation PR.
- **Foundation PR first** — shared exceptions, error constants, utility methods, base classes, interfaces, Flyway migrations — anything needed by 2+ features ships first.
- **Each feature = one PR** — a vertical slice (Controller + Service + Repository + Mapper + Tests) reviewable and mergeable independently.
- **Size limit** — if a feature touches more than ~8 files, split it further.

---

## Step 2 — Design Checklist (Answer Every Question)

### Architecture
- [ ] Where does this code live? Which package? Does a similar feature already exist to follow?
- [ ] What's the call chain? Controller → Service → Repository? Does it need a Mapper?
- [ ] Does this touch an existing class or create new ones?
  - If existing: can it be modified? Is the change small and contained? Does it break existing callers?
  - If breaking: **do not extend — create a new class instead**
- [ ] Is there a generated API interface (`*Api.java`) from `openapi-precon` to implement?

### Dependencies & Boundaries
- [ ] **Always inject interfaces, never implementations.** No exceptions.
- [ ] Does this class do ONE thing (SRP)? If parsing + validation + persistence in one method — split it.
- [ ] **Business logic in the Service layer only.** Never in Controllers (thin) or Repositories (data access).
- [ ] **Check shared libs first** — `CursorUtils`, `HeaderUtils`, `ApiErrorMessages`, validators. Only create a new util when nothing existing fits.
- [ ] **Prefer annotations for validation** — Jakarta Bean Validation via `x-field-extra-annotation`. Only business rules (state checks, cross-field, domain invariants) go in the Service.

### Data Flow
- [ ] What does the DB schema look like? Read the Flyway migration before writing any repository.
- [ ] Which tables are involved? Relationships (FK, unique constraints)?
- [ ] Does the repository return records directly (throwing `NotFoundException` internally)? No `.orElseThrow()` at call sites.
- [ ] What's the mapping strategy? Record ↔ DTO via MapStruct.

### Error Handling
- [ ] What error cases exist? Not found, duplicate, bad state, concurrent update?
- [ ] Do the required `ApiErrorMessages` constants exist? If not, add them to the foundation PR.
- [ ] What exception types are needed? Do they exist in `libs/models`? If not, add to foundation PR.

---

## Step 3 — Edge Case & Resilience Gate

**All 28 must be answered before implementation. N/A is acceptable with justification. Any NO = not ready.**

| # | Question | Answer | Justification |
|---|----------|--------|---------------|
| 1 | Idempotent? (safe to call twice with same input) | YES/NO/N/A | |
| 2 | Safe to retry? (no dirty state on failure) | YES/NO/N/A | |
| 3 | Rate-limited? (external calls throttled?) | YES/NO/N/A | |
| 4 | Auth protected? (`@PreAuthorize`, `xContextId` validated?) | YES/NO/N/A | |
| 5 | Concurrent-safe? (ETag / optimistic locking where needed?) | YES/NO/N/A | |
| 6 | Handles infra failure? (S3/SQS/DB down → graceful?) | YES/NO/N/A | |
| 7 | Transaction-safe? (no partial commits) | YES/NO/N/A | |
| 8 | Data integrity? (FK/unique constraints, no orphaned records) | YES/NO/N/A | |
| 9 | Null/empty inputs handled? (null IDs, empty lists, blank strings) | YES/NO/N/A | |
| 10 | Not-found handled? (deleted, wrong company, never existed) | YES/NO/N/A | |
| 11 | Invalid state transitions blocked? | YES/NO/N/A | |
| 12 | Payload/list size limits? (pagination edge cases) | YES/NO/N/A | |
| 13 | Pagination edge cases? (empty result, first page, last page) | YES/NO/N/A | |
| 14 | Cross-tenant isolation? (company A can't see company B) | YES/NO/N/A | |
| 15 | Audit trail? (who did what, when) | YES/NO/N/A | |
| 16 | Cascading deletes? (parent deleted → what happens to children) | YES/NO/N/A | |
| 17 | Timeout handling? (long-running ops, external calls) | YES/NO/N/A | |
| 18 | Error response consistency? (matches `ErrorResponse` schema) | YES/NO/N/A | |
| 19 | Logging sufficient? (traceable without leaking PII) | YES/NO/N/A | |
| 20 | Backward compatible? (existing clients unaffected) | YES/NO/N/A | |
| 21 | Best practice? (industry standards, not custom hacks) | YES/NO/N/A | |
| 22 | Cost efficient? (no unnecessary DB calls, S3 ops, API roundtrips) | YES/NO/N/A | |
| 23 | Best tradeoff? (simplest solution that meets requirements) | YES/NO/N/A | |
| 24 | Scalable? (works at 10x current load without redesign) | YES/NO/N/A | |
| 25 | Query performance? (indexed columns, no N+1, no full table scans) | YES/NO/N/A | |
| 26 | Memory efficient? (no loading entire tables, bounded collections) | YES/NO/N/A | |
| 27 | Minimal blast radius? (failure here doesn't break unrelated features) | YES/NO/N/A | |
| 28 | Reversible? (can be rolled back without data loss) | YES/NO/N/A | |

**Verdict: READY / NOT READY** — fix any NO before writing code.

---

## Step 4 — Plan Output

Document the plan before starting:

```
### PR #1 — Foundation (shared dependencies) ⬅ SHIPS FIRST
- ApiErrorMessages constants: {list}
- New exceptions in libs/models: {list}
- Flyway migrations: {list}
- Shared interfaces/utils: {list}

### PR #2 — {Feature A} (no deps on other feature PRs)
### PR #3 — {Feature B} (no deps on other feature PRs)
```

For each feature PR:
```
## Implementation Plan: {Feature Name}

### Files to Create
- path/to/NewFile.java — responsibility

### Files to Modify
- path/to/ExistingFile.java — what changes

### Files NOT Touched (owned by other features)
- path/to/OtherFile.java — belongs to {other feature}

### Pattern Reference
- Following: {ExistingClass} for {reason}

### Dependencies
- Injects: {interfaces}
- Uses: {shared utilities}
- From Foundation PR: {what it needs}
```
