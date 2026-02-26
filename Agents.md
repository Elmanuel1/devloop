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
