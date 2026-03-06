# Agent Reference — Tosspaper Email Engine

Quick reference for AI agents working on this codebase.

---

## Agent Pipeline — How Handoffs Work

**You (the main conversation) are the orchestrator.** You invoke agents in order, pass outputs between them, and run parallel agents when the architect splits work into independent features.

### Pipeline Flow

```
User Request
    │
    ▼
┌─────────────┐
│  ARCHITECT   │  Plan the work, split into features, validate edge cases
└─────┬───────┘
      │ outputs: task breakdown + per-feature implementation plans
      │
      ├── Foundation PR needed? ──► SPEC-WRITER (if API changes)
      │                           ──► MIGRATION-WRITER (if schema changes)
      │
      │ once foundation is done:
      │
      ├──┬──┬── parallel features ──┐
      │  │  │                       │
      ▼  ▼  ▼                      │
┌──────────────┐                   │
│ CODE-WRITER  │  (one per feature, run in parallel)
│ CODE-WRITER  │  each writes production code + tests
│ CODE-WRITER  │
└──────┬───────┘
       │ outputs: implemented code + passing tests (90% coverage)
       ▼
┌─────────────┐
│  REVIEWER    │  Audit ALL changed files (code + tests) against ALL rules
└──────┬──────┘
       │ outputs: violation report
       │
       ├── violations found? ──► DEBUGGER (fix violations, re-verify)
       │                         then back to REVIEWER
       │
       │ clean? ▼
       Done ✅
```

### How to Orchestrate (step by step)

1. **Invoke `architect`** with the full task description
   - It returns: task breakdown (foundation PR + feature PRs) with implementation plans
   - Read its output — it's your roadmap

2. **Foundation PR first** (if the architect says one is needed):
   - Invoke `spec-writer` if there are OpenAPI spec changes — **this is always a separate PR**
   - Invoke `migration-writer` if there are DB schema changes — **this is always a separate PR**
   - Invoke `reviewer` on the foundation code
   - Invoke `debugger` if violations found

3. **Feature PRs in parallel** (after foundation is merged):
   - Launch multiple `code-writer` agents simultaneously via parallel Task tool calls
   - Each gets ONE feature's implementation plan from the architect
   - **Each code-writer writes both production code AND tests** (90% coverage target)
   - They don't touch each other's files — the architect guarantees no overlap

4. **Review → Fix loop** (repeat until clean):
   - Invoke `reviewer` on each feature's changed files (code + tests)
   - If violations → invoke `debugger` with the violation list to fix them
   - Re-invoke `reviewer` — repeat until **zero violations**
   - Never stop with violations outstanding — the loop must reach PASS

### Parallel Execution with Worktrees

Each code-writer runs in an **isolated git worktree** so they don't step on each other's files. They commit as they go and create a PR when done.

```
Task(subagent_type="code-writer", isolation="worktree", prompt="Implement Feature A: {plan}")
Task(subagent_type="code-writer", isolation="worktree", prompt="Implement Feature B: {plan}")
Task(subagent_type="code-writer", isolation="worktree", prompt="Implement Feature C: {plan}")
```

All three run simultaneously in separate worktrees. Each:
1. Gets a fresh branch (`feature/{ticket}-{description}`)
2. Commits after each logical unit (repository, service, controller, mapper)
3. Creates a PR targeting `main` when done
4. The worktree is cleaned up automatically if no changes were made

---

## How to Invoke Agents

Agents are defined in `.claude/agents/` as markdown files. Invoke them via the **Task tool** with `subagent_type` matching the agent name.

### Syntax

```
Task(
  subagent_type = "{agent-name}",      # matches the "name" field in the agent's .md file
  description   = "short description",  # 3-5 words
  prompt        = "detailed task...",    # what the agent should do
  isolation     = "worktree",           # optional: gives agent its own git branch
  model         = "sonnet",             # optional: override model (default inherits)
)
```

### Invocation Examples

**Architect** (always first):
```
Task(subagent_type="architect", description="Plan extraction CRUD",
     prompt="Plan the implementation for all 7 extraction endpoints. Read the OpenAPI spec, V3.4 migration, and existing Tender patterns.")
```

**Spec-writer** (separate PR for API changes):
```
Task(subagent_type="spec-writer", description="Add extraction endpoints",
     prompt="Add the 7 extraction endpoints to openapi-precon.yaml. Follow the Tender endpoint patterns. Bump version to 0.7.0.")
```

**Migration-writer** (separate PR for schema changes):
```
Task(subagent_type="migration-writer", description="Write extraction migration",
     prompt="Write V3.5 migration for extraction_applications table. Read V3.4 for the existing extraction schema.")
```

**Code-writer** (one per feature, use worktree for parallel — writes code + tests):
```
Task(subagent_type="code-writer", isolation="worktree", description="Implement extraction CRUD",
     prompt="Implement Feature A per the architect's plan: {paste plan here}. Include unit + integration tests targeting 90% coverage.")
```

**Reviewer** (after code + tests are written):
```
Task(subagent_type="reviewer", description="Review extraction code",
     prompt="Review all files changed on branch feature/TOS-40-extraction-crud against all rules.")
```

**Debugger** (when something breaks):
```
Task(subagent_type="debugger", description="Fix compilation errors",
     prompt="Fix these compilation errors: {paste errors}. Changed files: {list files}.")
```

### Resuming an Agent

If an agent needs follow-up work, resume it with its ID to preserve context:
```
Task(subagent_type="debugger", resume="{agent-id}",
     prompt="The previous fix didn't work. Here's the new error: {paste error}")
```

---

## Subagents (`.claude/agents/`)

| Agent | Color | Purpose |
|-------|-------|---------|
| `architect` | blue | **MANDATORY first step** — assesses task, splits into features, validates design with 28-point edge case gate |
| `spec-writer` | yellow | Updates OpenAPI spec, bumps version, updates CHANGELOG — always a separate PR |
| `migration-writer` | cyan | Writes Flyway SQL migrations, regenerates jOOQ classes — always a separate PR |
| `code-writer` | green | Executes the architect's plan — writes production code + tests (90% coverage). Owns the full deliverable |
| `reviewer` | red | **Zero-tolerance** auditor — checks ALL rules (SOLID, DRY, testability, 15 patterns, hard rules) on code + tests |
| `debugger` | orange | Fixes any failure — compilation errors, test failures, reviewer violations, stack traces |

---

## Memory Files

Persistent memory lives at: `/Users/macbook/.claude/projects/-Users-macbook-tosspaper-email-engine/memory/`

| File | Purpose |
|------|---------|
| `MEMORY.md` | Top-level index — project structure, dependency rules, PR workflow, code generation, status flows, pre-existing issues |
| `testing.md` | Testing patterns — Spock mocking gotchas, exception constructor signatures, Lombok patterns, proto naming collisions, GrpcAuthenticationContext |
| `patterns.md` | 15 strict code patterns from PR reviews — error constants, thin controllers, MapStruct, CursorUtils, jOOQ typed accessors, etc. |

### Key Rules from Memory

- **Never hardcode dependency versions** — use `gradle/libs.versions.toml`
- **Never hardcode error strings** — use `ApiErrorMessages` constants
- **Exceptions go in `libs/models`** (`com.tosspaper.models.exception`)
- **Generated imports**: `com.tosspaper.precon.generated.model.*` / `com.tosspaper.precon.generated.api.*`
- **Repository `findById`**: returns `Optional` — caller uses `.orElseThrow()` or `.orElse(null)`
- **PRs always target `main`** — separate PRs for spec, migrations, code
- **Bump version + CHANGELOG** when changing `openapi-precon` spec

---

## Skills (`.claude/skills/`)

| Skill | Trigger | What It Does |
|-------|---------|-------------|
| `code-design` | Review code quality, prepare for testing | Analyzes code for SOLID, DRY, testability. References: `SOLID.md`, `DRY.md`, `TESTABILITY.md`, `REFACTORING.md`. Scripts: `verify-compile.sh`, `check-style.sh`, `compile-check.sh`, `format-code.sh`, `regenerate-sources.sh` |
| `test-unit` | Write unit tests for service classes | Spock mocks for services with injected deps. References: `STRUCTURE.md`, `MOCKING.md`, `EXAMPLES.md`, `JACOCO.md`. NO validation testing — trust API layer |
| `test-integration` | Write integration tests for repos/controllers | Testcontainers, DB tests, API endpoint tests, Bean Validation. References: `SETUP.md`, `REPOSITORY.md`, `CONTROLLER.md`, `VALIDATION.md`, `JACOCO.md`. Scripts: `run-tests.sh`, `run-tests-coverage.sh`, `run-specific-test.sh`, `check-coverage.sh` |
| `fix-tests` | Fix failing tests | Analyzes test reports, fixes all errors, re-runs. Script: `parse-failures.sh` |
| `gradle-deps` | Manage Gradle dependencies | Enforces version catalog usage, prevents hardcoded versions. References: `VERSION-CATALOG.md`, `BOM.md` |

### Built-in Skills (always available)

| Skill | Trigger |
|-------|---------|
| `keybindings-help` | Customize keyboard shortcuts |
| `web-design-guidelines` | Review UI for Web Interface Guidelines |
| `web-interface-guidelines` | Review UI for Vercel guidelines |
| `vercel-react-best-practices` | React/Next.js performance optimization |
| `vercel-composition-patterns` | React composition patterns |
| `find-skills` | Discover installable skills |

### Atlassian Skills (plugin)

| Skill | Trigger |
|-------|---------|
| `atlassian:triage-issue` | Triage bugs, search Jira for duplicates |
| `atlassian:capture-tasks-from-meeting-notes` | Extract action items → Jira tasks |
| `atlassian:generate-status-report` | Jira → Confluence status reports |
| `atlassian:search-company-knowledge` | Search Confluence/Jira knowledge base |
| `atlassian:spec-to-backlog` | Convert Confluence specs → Jira backlog |

---

## Hooks

Configured in `.claude/settings.json`. Run automatically on `Write` and `Edit`:

| Hook | Script | Purpose |
|------|--------|---------|
| PostToolUse | `.claude/hooks/check-style.sh` | Checks code style after edits |
| PostToolUse | `.claude/hooks/check-coverage.sh` | Checks test coverage after edits |

Other scripts in `.claude/hooks/`:
- `sync-claude-to-cursor.sh` — Sync Claude settings to Cursor IDE
- `sync-cursor-to-claude.sh` — Sync Cursor settings to Claude

---

## MCP Servers

| Server | Tools | Use For |
|--------|-------|---------|
| `aws-documentation-mcp-server` | `search_documentation`, `read_documentation`, `recommend` | AWS docs lookup |
| `terraform-mcp-server` | `ExecuteTerraformCommand`, `SearchAwsProviderDocs`, `RunCheckovScan`, etc. | Terraform/Terragrunt operations |
| `pencil` | `batch_design`, `batch_get`, `get_screenshot`, etc. | Design file (.pen) editing |
| `playwright` | `browser_navigate`, `browser_click`, `browser_snapshot`, etc. | Browser automation/testing |
| `Atlassian` (plugin) | Jira + Confluence CRUD | Issue tracking, documentation |
| `Github` (plugin) | PR, issue, repo operations | GitHub integration |

---

## Precon Extraction Pipeline (TOS-37 / TOS-38 / TOS-39)

The precon module implements a fully asynchronous document extraction pipeline that submits PDFs to the Reducto AI service and stores results back into the database.

### End-to-End Data Flow

```
POST /v1/extractions  (TOS-37)
        │
        ▼
ExtractionPollJob  (@Scheduled, SKIP LOCKED)
        │  claims PENDING → PROCESSING via PreconExtractionRepository.claimNextBatch()
        ▼
ExtractionPipelineRunner  (fan-out via CompletableFuture, virtual-thread executor)
        │  one future per extraction
        ▼
ExtractionWorker  (TOS-38 — per-extraction logic)
        │
        ├─ 1. Download bytes from S3   (readContentBytes → S3Client)
        ├─ 2. Classify document        (PdfBoxDocumentClassifier → ConstructionDocumentType)
        │     UNKNOWN → skip (not a failure)
        ├─ 3. Submit to Reducto        (HttpReductoClient.submit → POST /extract)
        ├─ 4. Store taskId             (PreconExtractionRepository.updateDocumentExternalIds)
        │     extractions.document_external_ids JSONB  Map<documentId, externalTaskId>
        └─ 5. Store fileId             (TenderDocumentRepository.updateExternalFileId)
              tender_documents.external_file_id TEXT
        │
        ▼  (async — Reducto calls back via webhook)
POST /internal/reducto/webhook  (TOS-39 — PreconReductoWebhookController)
        │  signature verified via WebhookVerifier (Svix)
        ▼
ReductoWebhookHandlerService
        │
        ├─ Look up extraction by taskId  (PreconExtractionRepository.findByDocumentExternalTaskId)
        ├─ Fetch result                  (ProcessingService.getExtractTask)
        └─ TODO [TOS-38]: persist fields to extraction_fields via ExtractionFieldRepository
        │
        ▼
ConflictDetector  (TOS-39 — field deduplication)
        │  normalises nested JSONB (ORDER_MAP_ENTRIES_BY_KEYS, recursive)
        └─ compares new field value against existing to detect conflicts
```

### Key Classes (all in `com.tosspaper.precon`)

| Class | Role |
|-------|------|
| `ExtractionPollJob` | Scheduler — claims batches + reaper (uses `ExtractionProcessingProperties`) |
| `ExtractionPipelineRunner` | Fan-out — one `CompletableFuture` per extraction |
| `ExtractionWorker` | Per-extraction: classify → submit → persist IDs |
| `PdfBoxDocumentClassifier` | PDF text extraction via PDFBox, keyword-score classification |
| `DocumentClassifier` | Interface — accepts `byte[]` (already buffered from S3) |
| `ConstructionDocumentType` | Enum implementing `TenderDocumentType`; 6 exclusive types + UNKNOWN |
| `HttpReductoClient` | Java `HttpClient`, fire-and-forget with webhook callback |
| `ReductoProperties` | `reducto.*` config — baseUrl, apiKey, webhookBaseUrl, batchSize, etc. |
| `ExtractionProcessingProperties` | `extraction.processing.*` — provider-agnostic poll config |
| `PreconReductoWebhookController` | Receives Reducto callbacks at `POST /internal/reducto/webhook` |
| `ReductoWebhookHandlerService` | Business logic for webhook: look up extraction, fetch result |
| `WebhookVerifier` | Svix signature verification (skips if `svixSecret` is blank) |
| `ReductoWebhookProperties` | `reducto.webhook.*` — svixSecret |
| `ConflictDetector` | Normalises JSONB and compares fields for conflict detection |
| `PreconExtractionRepository` | Interface — claim, reap, mark, getDocumentExternalIds, updateDocumentExternalIds, findByDocumentExternalTaskId |
| `TenderDocumentRepository` | Interface — includes `updateExternalFileId` |

### Database Schema (post TOS-38 / TOS-39)

```sql
-- extractions table (V3.4 + V3.5 + V3.8 + V3.12)
document_external_ids  JSONB  NOT NULL DEFAULT '{}'  -- Map<documentId, externalTaskId>
external_task_id       VARCHAR(255)                  -- legacy column kept for jOOQ 0.1.8 compat;
                                                     --  dropped in future migration with jOOQ 0.1.9

-- tender_documents table (V3.1 + V3.12)
external_file_id       TEXT                          -- Reducto file ID from upload response
```

### Configuration

```yaml
reducto:
  base-url: "${REDUCTO_BASE_URL}"
  api-key: "${REDUCTO_API_KEY}"
  webhook-base-url: "${SERVICE_PUBLIC_URL}"
  webhook-path: "/internal/reducto/webhook"
  batch-size: 20
  stale-minutes: 15
  timeout-seconds: 30
  webhook:
    svix-secret: "${REDUCTO_WEBHOOK_SVIX_SECRET}"  # leave blank to skip verification

extraction:
  processing:
    thread-pool-size: 5
    batch-size: 20
    stale-minutes: 15
```

### jOOQ Compatibility Note

`flyway-jooq-classes` 0.1.8 was generated before V3.12. It includes `EXTERNAL_TASK_ID` but not `DOCUMENT_EXTERNAL_IDS`. `PreconExtractionRepositoryImpl` accesses `document_external_ids` via raw SQL to avoid the mismatch. Version 0.1.9 (not yet published) will be generated from the post-V3.12 schema.

---

## Build Commands

```bash
# Compile
./gradlew :libs:api-tosspaper:compileJava --rerun-tasks

# Run tests
./gradlew :libs:api-tosspaper:test --rerun-tasks
./gradlew :libs:api-tosspaper:test --tests "*TenderService*"

# Full build
./gradlew :services:everything:build

# Run app
./gradlew :services:everything:bootRun

# LocalStack
docker compose down localstack -v && docker compose up localstack -d
```
