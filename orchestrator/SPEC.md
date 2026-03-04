# Orchestrator — Architecture & Implementation

**Updated:** 2026-03-02 v4
**Runtime:** Python 3.9+ (in-process poller, subprocess agents via `claude -p`)
**State:** File-based (default) or SQLite (opt-in)

---

## 1. Overview

The orchestrator is an in-process Python event loop that:
1. **Polls** GitHub, Confluence, and Jira for state changes (watches)
2. **Receives** webhooks from GitHub/Jira (HTTP server on port 9400)
3. **Listens** on Slack Socket Mode for user messages
4. **Routes** events to the right sub-agent (`claude -p` subprocess)
5. **Tracks** all state (designs, sessions, watches, events) in a persistent store

Agents are spawned as `claude -p` subprocesses with optional `--worktree` isolation. The orchestrator never writes production code — it only makes routing decisions.

---

## 2. File Layout

```
orchestrator/
├── poll.py              # Watch-driven event loop, webhook server, Slack listener
├── state.py             # State library: provider interfaces, FileStateProvider, State class
├── state_cli.py         # CLI for state management (design/session/watch/events/log/migrate)
├── store_sqlite.py      # SQLite backend (drop-in replacement for FileStateProvider)
├── cli_utils.py         # Shared CLI helpers (flag parsing, dotenv loading)
├── jira.py              # Jira REST API CLI wrapper
├── confluence.py        # Confluence REST API CLI wrapper
├── gh_review.py         # GitHub PR review helpers
├── gh_comment.py        # GitHub PR comment helpers
├── migrations/          # Flyway-style versioned SQL migrations
│   ├── V001__initial_schema.sql
│   └── V002__add_channel_column.sql
├── test_state.py        # Tests for state.py + FileStateProvider
├── test_store_sqlite.py # Tests for SQLite backend
├── test_poll.py         # Tests for poll.py handlers
└── .orchestrator/       # Runtime state directory (gitignored)
    ├── watches.json
    ├── events/{channel}/*.json
    ├── dead_events/*.json
    ├── designs/*.json
    ├── sessions/*.json
    ├── log.jsonl
    └── state.db         # Only if STATE_BACKEND=sqlite
```

---

## 3. Provider Architecture (ISP)

State is abstracted behind 5 per-entity interfaces (Interface Segregation Principle):

```python
class WatchProvider(ABC):
    read_watches() -> List[dict]
    write_watches(watches: List[dict]) -> None

class EventProvider(ABC):
    push_event(event: dict, channel: str = "system") -> None
    pop_event(channel: Optional[str] = None) -> Optional[dict]
    read_events(channel: Optional[str] = None) -> List[dict]
    nack_event(event: dict) -> None

class DeadEventProvider(ABC):
    list_dead_events() -> List[dict]
    retry_dead_events() -> int

class DesignProvider(ABC):
    read_design(design_id: str) -> Optional[dict]
    write_design(design: dict) -> None
    delete_design(design_id: str) -> bool
    list_designs() -> List[dict]

class SessionProvider(ABC):
    read_session(issue_key: str) -> Optional[dict]
    write_session(issue_key: str, session: dict) -> None
    delete_session(issue_key: str) -> bool
    list_sessions() -> List[dict]
```

Both `FileStateProvider` and `SQLiteStateProvider` implement all 5 interfaces via multiple inheritance.

The **action log** is always file-based JSONL (`log.jsonl`) — not abstracted into a provider.

---

## 4. Multi-Channel Event Queue

Events are routed to channels by type prefix:

| Prefix | Channel | Examples |
|--------|---------|----------|
| `ci:` | ci | ci:passed, ci:failed |
| `pr:` | pr | pr:approved, pr:comment, pr:merged |
| `page:` | design | page:comment, page:approved |
| `task:` | task | task:requested |
| other | system | watch:expired, agent:completed |

**Round-robin pop** prevents channel starvation — `pop_event()` cycles through all 5 channels.

**File backend**: events stored in `events/{channel}/*.json` subdirectories.
**SQLite backend**: events table has a `channel` column with index.

### Poison protection

Events track `_attempts`. After 3 failed attempts, events are dead-lettered (moved to `dead_events`). Dead events can be inspected and retried via CLI.

### Nack

Failed events are re-queued with `nack_event()` which increments `_attempts` and routes back to the original channel.

---

## 5. SQLite Backend

Opt-in via `STATE_BACKEND=sqlite` in `.env`. Drop-in replacement for file-based storage.

### Migrations

Flyway-style versioned SQL files in `migrations/`:

```
V001__initial_schema.sql   — CREATE TABLE watches, events, dead_events, designs, sessions + indexes
V002__add_channel_column.sql — ALTER TABLE events ADD channel + backfill from type prefix
```

Migrations are **repeatable** — safe to run multiple times:
- V001 uses `CREATE TABLE IF NOT EXISTS`
- V002's `ALTER TABLE ADD COLUMN` tolerates "duplicate column" errors
- Backfill UPDATEs are idempotent (WHERE guards)

Schema version tracked in `schema_version` table. New migrations are auto-applied on startup.

### Auto-migration from files

When `STATE_BACKEND=sqlite` is set and no `state.db` exists, the provider auto-migrates all file-based state (watches, events, designs, sessions) into a new SQLite database.

---

## 6. Event Types

```
# Source control
ci:passed, ci:failed

# Pull requests
pr:approved, pr:comment, pr:changes_requested, pr:merged

# Confluence documents
page:approved, page:comment, page:needs-fix

# Task intake
task:requested

# Internal
agent:completed, watch:expired, stage:completed
```

---

## 7. Watch System

The poller maintains a list of watches — periodic checks against external systems. Each watch has:
- **type**: `pr:ci`, `pr:review`, `pr:merge`, `confluence:review`
- **interval**: seconds between polls (default 30)
- **expiresAt**: auto-expiry with TTL per type
- **dedup keys**: prevents duplicate watches of the same type+target

Watch handlers (in `poll.py`) are self-declaring classes that inherit from `WatchHandler`:

| Handler | Watch Type | What It Checks |
|---------|-----------|----------------|
| PRCIHandler | pr:ci | GitHub CI status checks |
| PRReviewHandler | pr:review | GitHub PR review state |
| PRMergeHandler | pr:merge | GitHub PR merge state |
| ConfluenceReviewHandler | confluence:review | Confluence page approval + comments |

When a watch resolves (e.g., CI passes), the handler pushes an event to the appropriate channel and optionally removes itself.

---

## 8. Poller (`poll.py`)

The poller is the main process:

1. **Loads watches** from state
2. **Polls** in parallel (ThreadPoolExecutor, configurable workers)
3. **Runs webhook server** (port 9400) for GitHub/Jira push events
4. **Runs Slack Socket Mode** listener for user messages
5. **Expires watches** past their TTL
6. **SIGUSR1** reloads `.env` config without restart

Agents are spawned as `claude -p` subprocesses with PTY for interactive I/O.

---

## 9. Event Routing (Orchestrator Agent)

The orchestrator agent (`.claude/agents/orchestrator.md`) consumes events and routes them:

| Event | Action |
|-------|--------|
| task:requested (feature) | Create design → spawn architect |
| task:requested (bug) | Create Jira → spawn debugger |
| task:requested (chore) | Create Jira → spawn code-writer |
| page:comment | Spawn architect with comment context |
| page:approved | Remove watch → spawn architect for Jira breakdown → code-writers |
| ci:failed | Resume code-writer session |
| ci:passed | Spawn reviewer → register pr:review watch |
| pr:approved | Merge PR → transition Jira → check siblings |
| pr:comment | Resume code-writer session |
| pr:changes_requested | Resume code-writer session |
| pr:merged | Transition Jira → spawn next features if foundation |

---

## 10. Idempotency

| Operation | Guard |
|-----------|-------|
| Watch creation | Dedup by type + key fields |
| Confluence page | findPage first → update if exists |
| Jira issue | Check existing by summary |
| GitHub merge | Check pr.merged before merging |
| Code-writer PR | findPR(branch) → skip if exists |
| SQLite migrations | Schema version table + IF NOT EXISTS |

---

## 11. Running

```bash
# Start poller (main process)
cd orchestrator && uv run poll.py

# State management
python3 state_cli.py summary
python3 state_cli.py design list
python3 state_cli.py events list

# Run tests
python3 -m pytest test_state.py test_poll.py test_store_sqlite.py -v
```
