# TossPaper AI Orchestrator — Phase 1 (MVP) Implementation Plan

**Date:** 2026-02-26 (Updated: 2026-02-27 v3)
**Scope:** Phase 1 — Bun + Hono webhooks, better-queue, `claude -p` agent runner, Jira/Confluence/GitHub/Slack integrations, SQLite state.
**Review Status:** v3 — Major revision from critique session. Simplified from v2.

---

## 1. Key Design Decisions (v3 Changes from v2)

| Decision | v2 | v3 |
| --- | --- | --- |
| Entry point | Human creates Jira issue, moves to "Design" | Human messages orchestrator via Slack. No Jira issue at start |
| Jira issue creation | Subtasks at design time | All Jira issues (parent + subtasks) created at approval time from accepted task breakdown |
| Agents | researcher, architect, diagram_gen, planner, code_writer, test_writer | **Two agents**: architect (opus) and code_writer (opus). Reviewer as gate |
| Event routing | `config.eventRouting` declarative map | Handlers self-declare via `matches(event)`. No central routing map |
| Webhook architecture | Hardcoded per-source handlers | Single `POST /webhook/:source` route. Separate verifier + parser per source |
| Agent communication | Handlers call agents directly | Agents listen on queues, report back to orchestrator. Orchestrator decides next step |
| Output passing | Content in events | File-based. Agents write files, pipeline passes paths only |
| Foundation ordering | Single agent decides parallel/sequential | Orchestrator enforces: spec → migrations → shared code |
| Messaging platforms | Multiple (Slack, Telegram, WhatsApp) | Slack only (intake + notifications) |
| Model strategy | sonnet with escalation to opus | Opus for both agents. Downgrade later when confident |
| Multiple designs | Haiku resolver for disambiguation | One design at a time |
| Pipeline config | Typed data object driving execution | Hardcoded two agents |

---

## 2. Source Categories

| Role | Source | What it does |
| --- | --- | --- |
| **Task intake** | Slack | Human sends task requests + replies |
| **System events** | GitHub | CI results, PR reviews, PR comments, merges |
| **System events** | Jira | Written to only — never triggers orchestrator |
| **System events** | Confluence | Polled — page approvals, new comments |
| **Notifications** | Slack | Design ready, PR ready, merged, errors, completion |

Jira is write-only. The orchestrator creates issues and transitions them but never receives webhooks from Jira in Phase 1. Confluence is polled (every 60s), not webhook-driven.

---

## 3. Event System

### 3.1 Event Hierarchy (Abstract by Domain, Not by Tool)

```typescript
// --- Base ---
interface OrchestratorEvent {
  id: string
  source: string
  type: string
  raw: unknown
}

// --- Abstract by domain (tool-agnostic) ---
interface MessagingEvent extends OrchestratorEvent {
  message: string
  senderId: string
  senderName: string
  ack: (text: string) => Promise<void>  // immediate reply only
}

interface SourceControlEvent extends OrchestratorEvent {
  prNumber: number
  branch: string
}

interface DocumentEvent extends OrchestratorEvent {
  pageId: string
  designId: string
}

// --- Concrete events ---
interface TaskRequestEvent extends MessagingEvent {
  type: 'task:requested'
}

interface CiFailedEvent extends SourceControlEvent {
  type: 'ci:failed'
  logs: string
}

interface CiPassedEvent extends SourceControlEvent {
  type: 'ci:passed'
}

interface PrApprovedEvent extends SourceControlEvent {
  type: 'pr:approved'
}

interface ChangesRequestedEvent extends SourceControlEvent {
  type: 'pr:changes_requested'
  comments: string[]       // OVERRIDE: array, not scalar
}

interface PrMergedEvent extends SourceControlEvent {
  type: 'pr:merged'
}

interface PrCommentEvent extends SourceControlEvent {
  type: 'pr:comment'
  comments: string[]       // OVERRIDE: array, not scalar
}

interface PageApprovedEvent extends DocumentEvent {
  type: 'page:approved'
}

interface NewCommentEvent extends DocumentEvent {
  type: 'page:comment'
  comments: string[]       // OVERRIDE: array, not scalar, plural name
}

// --- Internal orchestrator events ---
interface AgentCompletedEvent extends OrchestratorEvent {
  type: 'agent:completed'
  agentName: string
  designId: string
  outputKey: string
  outputPath: string    // file path, not content
}

interface StageCompletedEvent extends OrchestratorEvent {
  type: 'stage:completed'
  designId: string
  stage: string
}
```

### 3.2 Handler Registration (Self-Declaring)

```typescript
interface TaskHandler {
  queue: string
  matches(event: OrchestratorEvent): boolean
  handle(event: OrchestratorEvent): Promise<void>
}

// Each handler declares what it responds to
const handlers: TaskHandler[] = [designHandler, feedbackHandler, approvalHandler, ...]

// Dispatch — loops handlers, first match wins
function dispatch(event: OrchestratorEvent) {
  const handler = handlers.find(h => h.matches(event))
  if (!handler) { logger.warn('Unhandled event', { type: event.type }); return }
  queues[handler.queue].push(event)
}
```

---

## 4. Webhook Architecture

### 4.1 Interfaces

```typescript
// Security — separate from parsing
interface WebhookVerifier {
  verify(c: Context): Promise<void>
}

// Data transformation — separate from security
interface EventParser<T extends OrchestratorEvent = OrchestratorEvent> {
  parse(input: any): Promise<T[]>
}

// Outbound only
interface NotificationChannel {
  send(message: string, threadTs?: string): Promise<void>  // OVERRIDE: added optional threadTs
}
```

### 4.2 Single Webhook Route

```typescript
const sources = new Map([
  ['github', { verifier: new GitHubVerifier(), parser: new GitHubEventParser() }],
  ['slack',  { verifier: new SlackVerifier(),  parser: new SlackEventParser() }],
])

app.post('/webhook/:source', async (c) => {
  const source = sources.get(c.req.param('source'))
  if (!source) return c.json({ error: 'unknown' }, 404)

  await source.verifier.verify(c)
  const events = await source.parser.parse(c)

  for (const event of events) {
    dispatch(event)
  }

  return c.json({ ok: true })
})
```

### 4.3 Issue Key Resolution (Per Parser)

Each parser resolves identifiers from its own source. No shared base method.

* **GitHub parser**: Extracts issue key from branch name (`feature/TOS-40-payments` → `TOS-40`). Only active during implementation.
* **Confluence parser**: Extracts design ID from page title. Only active during design review.
* **Slack parser**: No issue key needed — new task requests or replies to active design.

---

## 5. Orchestrator Pattern

Agents don't run handlers directly. The orchestrator is the central brain.

```
Webhook → Parser → Event → dispatch → drops task in agent-specific queue
                                            ↓
                              Agent worker picks up from its queue
                              Spawns claude -p
                              Agent writes output files
                              Worker pushes AgentCompletedEvent to orchestrator queue
                                            ↓
                              Orchestrator picks up
                              Reads route map: agentType:taskType → handler
                              Decides next step
                              Pushes to next agent queue
```

### 5.1 Route Map

```typescript
type RouteKey = `${string}:${string}`

const routes = new Map<RouteKey, (event: AgentCompletedEvent) => Promise<void>>([
  ['architect:design',       async (e) => { /* AI reviewer gate → publish to Confluence */ }],
  ['architect:feedback',     async (e) => { /* AI reviewer gate → update Confluence page */ }],
  ['code_writer:implementation', async (e) => { /* verify PR → store state → start CI + review */ }],
  ['code_writer:ci_fix',     async (e) => { /* update ci attempts */ }],
  ['code_writer:review_fix', async (e) => { /* push back to reviewer */ }],
  ['code_writer:human_feedback', async (e) => { /* push to reviewer */ }],
])

queues.orchestrator.on('task', async (event) => {
  const key: RouteKey = `${event.agentType}:${event.taskType}`
  const handler = routes.get(key)
  if (!handler) { logger.warn('Unhandled', { key }); return }
  await handler(event)
})
```

---

## 6. File-Based Outputs

Agents write to files. Pipeline passes paths, never content.

### 6.1 Directory Structure (Scoped per Design)

```
/designs/
  {designId}/                              # UUID generated at intake
    design/
      design_doc.md                        # architect output (research + design + diagrams)
      design_doc.r1.md                     # revision 1 (after feedback)
      design_doc.r2.md                     # revision 2
    implementation/
      foundation/
        {issueKey}/                        # e.g. TOS-41 — created at approval
          (code written by agent in worktree)
      features/
        {issueKey}/                        # e.g. TOS-42 — one dir per subtask
          (code written by agent in worktree)
        {issueKey}/                        # TOS-43
        {issueKey}/                        # TOS-44
```

### 6.2 Scoping Rules

* **Design-level**: `designId` isolates designs. Two designs never touch the same directory.
* **Revision-level**: Feedback produces `design_doc.r1.md`, `r2.md`. Previous versions preserved.
* **Subtask-level**: `issueKey` isolates parallel feature work. Concurrent code_writers each write to their own directory.

### 6.3 AgentCompletedEvent Carries Path, Not Content

```typescript
// Agent worker after claude -p exits
queues.orchestrator.push({
  type: 'agent:completed',
  agentName: 'architect',
  designId,
  outputKey: 'design_doc',
  outputPath: '/designs/{designId}/design/design_doc.md',  // path only
})
```

---

## 7. File Structure

All orchestrator code lives in `orchestrator/` at the project root. The existing `.claude/agents/` specs remain in place — the orchestrator reads them at runtime.

```
orchestrator/
├── package.json, tsconfig.json, bunfig.toml, .env.example, .env
├── store/
│   └── migrations/
│       ├── 001_initial.sql               # pr_state + design tables, read at startup
│       └── (future migrations)
├── src/
│   ├── index.ts                          # Hono server bootstrap, single POST /webhook/:source route,
│   │                                     #   Confluence polling (setInterval), health/status endpoints,
│   │                                     #   POST /retry/{prNumber}/ci and /retry/{prNumber}/review endpoints,
│   │                                     #   POST /trigger/{designId} manual re-trigger
│   │
│   ├── config.ts                         # All tunables: queue concurrency, max retries, polling interval,
│   │                                     #   agent timeout (1hr), heartbeat timeout (10min), Confluence poll
│   │                                     #   interval (60s), design output base path
│   │
│   ├── types.ts                          # All TypeScript types
│   │
│   ├── dispatch.ts                       # Handler registry (TaskHandler[]), dispatch(event) function.
│   │
│   ├── queue.ts                          # TaskQueue + TaskQueueFactory interfaces (abstract)
│   │
│   ├── queue.memory.ts                   # MemoryQueueFactory — wraps better-queue behind TaskQueueFactory
│   │
│   ├── queues.ts                         # createQueues() — wires MemoryQueueFactory + config + agent workers
│   │
│   ├── store.ts                          # SQLite store (bun:sqlite)
│   │
│   ├── agents.ts                         # PURE runner. Spawns claude -p, 1hr timeout, 10min heartbeat
│   │
│   ├── orchestrator.ts                   # Route map: Map<RouteKey, handler function>
│   │
│   ├── handlers/
│   │   ├── design.ts                     # matches: type === 'task:requested'
│   │   ├── feedback.ts                   # matches: type === 'page:comment'
│   │   ├── approval.ts                   # matches: type === 'page:approved'
│   │   ├── ci-fix.ts                     # matches: type === 'ci:failed'
│   │   ├── ci-passed.ts                  # matches: type === 'ci:passed'
│   │   ├── human-feedback.ts             # matches: type === 'pr:changes_requested' OR 'pr:comment'
│   │   ├── review.ts                     # matches: type === 'review:code'
│   │   └── merge.ts                      # matches: type === 'pr:approved'
│   │
│   ├── webhooks/
│   │   ├── verify/
│   │   │   ├── github.ts                 # HMAC-SHA256 signature verification
│   │   │   └── slack.ts                  # Slack signing secret + replay protection
│   │   └── parsers/
│   │       ├── github.ts                 # GitHub webhook → typed events
│   │       ├── slack.ts                  # Slack Events API → TaskRequestEvent
│   │       └── confluence.ts             # Polling results → PageApprovedEvent / NewCommentEvent
│   │
│   ├── polling/
│   │   └── confluence.ts                 # setInterval 60s, checks active designs
│   │
│   ├── integrations/
│   │   ├── jira.ts                       # Jira REST API v3 client
│   │   ├── confluence.ts                 # Confluence REST API v2 client
│   │   ├── github.ts                     # GitHub REST via Octokit
│   │   └── slack.ts                      # Slack Events API + Webhook
│   │
│   └── utils/
│       ├── parse-plan.ts                 # Extract Foundation + Features from design_doc.md
│       ├── classify-failure.ts           # CI log → failure type classification
│       ├── parse-agent-output.ts         # claude -p JSON → ParsedAgentOutput
│       ├── worktree.ts                   # Git worktree lifecycle
│       └── logger.ts                     # JSON structured logger
│
└── test/                                 # Mirrors src/ structure
```

---

## 8. Queue Architecture

| Queue | Concurrency | Task Types |
| --- | --- | --- |
| architect | 2 | design, feedback |
| codeWriter | 3 | feature, ci_fix, human_feedback |
| reviewer | 2 | review (design audit gate + code review) |
| orchestrator | 1 (sequential) | routing decisions, merges, stage transitions |

---

## 9. SQLite Store

### 9.1 Schema

```sql
CREATE TABLE IF NOT EXISTS design (
  id TEXT PRIMARY KEY,
  description TEXT,
  stage TEXT DEFAULT 'design',
  status TEXT DEFAULT 'running',
  page_id TEXT,
  parent_key TEXT,
  review_attempts INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS design_output (
  design_id TEXT NOT NULL,
  output_key TEXT NOT NULL,
  output_path TEXT NOT NULL,
  PRIMARY KEY (design_id, output_key),
  FOREIGN KEY (design_id) REFERENCES design(id)
);

CREATE TABLE IF NOT EXISTS pr_state (
  pr_number INTEGER PRIMARY KEY,
  design_id TEXT NOT NULL,
  stage TEXT NOT NULL,
  issue_key TEXT,
  parent_key TEXT,
  feature_slug TEXT,
  ci_status TEXT DEFAULT 'pending',
  review_status TEXT DEFAULT 'pending',
  ci_attempts INTEGER DEFAULT 0,
  review_attempts INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY (design_id) REFERENCES design(id)
);
```

---

## 10. Agent Runner (agents.ts)

```typescript
interface AgentOptions {
  worktree?: boolean
  branch?: string
  keepWorktree?: boolean
  cwd?: string
  timeoutMs?: number        // default: 1 HOUR
  heartbeatMs?: number      // default: 10 MINUTES
  allowedTools?: string[]
}

async function claudeAgent(agent: string, prompt: string, opts?: AgentOptions): Promise<AgentResult>
```

---

## 11. Handler Logic (Pseudocode)

### handleDesign (matches: 'task:requested')
1. Generate designId (UUID)
2. Create directory: /designs/{designId}/
3. Store design: { id, description, stage: 'design', status: 'running' }
4. Ack via Slack: "Got it — starting design"
5. Push to architect queue: { designId, outputPath }

### Orchestrator route: architect:design
1. Store output path under 'design_doc'
2. AI REVIEWER GATE: reviewer checks design, architect revises if needed (max 3x)
3. Create Confluence page from design_doc.md content
4. Set page state to "In Review"
5. Notify: "Design ready for review: [link]"

### handleFeedback (matches: 'page:comment')
1. Check reviewAttempts < 10
2. Increment reviewAttempts
3. Push to architect queue with current design doc path + feedback

### handleApproval (matches: 'page:approved')
1. Update store: { status: 'approved' }
2. Dispatch StageCompletedEvent { stage: 'design' }

### Orchestrator route: stage:completed (design → implementation)
1. Parse task breakdown from latest design_doc
2. Create Jira parent + subtasks
3. If foundation exists: push to code_writer queue
4. No foundation: push all feature tasks
5. Notify: "Implementation started"

### handleCiFix (matches: 'ci:failed')
1. TRIAGE: classifyFailure(logs)
2. Check ciAttempts < 10 before retrying
3. Push to code_writer queue with logs + branch

### handleCiPassed (matches: 'ci:passed')
1. Update store: { ci_status: 'passing' }
2. checkReadyForHuman() — if both pass → notify human

### handleHumanFeedback (matches: 'pr:changes_requested' OR 'pr:comment')
1. Push to code_writer queue with comments + branch

### handleMerge (matches: 'pr:approved')
1. Check pr.merged (idempotent)
2. Squash merge via GitHub
3. Transition Jira subtask → Done
4. If foundation PR → push all feature tasks
5. If feature PR → checkAllSiblingsMerged → parent Done

---

## 12. Idempotency Guards

| Operation | Guard |
| --- | --- |
| confluence.createPage | findPage(designId) first → update if exists |
| jira.createIssue | Check existing by summary → skip if exists |
| jira.createSubTask | getSubTasks(parentKey) → skip if summary match |
| github.mergePR | getPR(number) → skip if pr.merged === true |
| code_writer PR creation | findPR(branch) → skip if PR exists |
| jira.transition | Naturally idempotent |
| confluence.updatePage | Naturally idempotent |
| store.set | Naturally idempotent |
| slack.send | Acceptable duplicate |

---

## 13. CI Failure Triage

| Classification | Examples | Action |
| --- | --- | --- |
| agent-fixable | Test failure, lint error, type error, missing import | Auto-retry via code_writer |
| environment | Missing env var, Docker build fail, dependency resolution | Notify immediately, don't retry |
| flaky | Intermittent timeout, network blip | Retry once, escalate if repeats |

---

## 14. Retry Reset Endpoints

```
POST /retry/{prNumber}/ci       → Reset ciAttempts to 0, re-enqueue ci_fix
POST /retry/{prNumber}/review   → Reset reviewAttempts to 0, re-enqueue review
POST /trigger/{designId}        → Manually re-trigger a design task
```

---

## 15. Integration Clients

| Client | Auth | Key Methods |
| --- | --- | --- |
| **Jira** | Basic (email:token) | createIssue, createSubTask, getSubTasks, transition, addComment |
| **Confluence** | Basic (email:token) | createPage, updatePage, findPage, getContentState, setContentState, getPagesInReview, getNewComments |
| **GitHub** | Octokit (PAT) | getPR, findPR, mergePR, getPRReviewComments, getCheckRunLogs, getPRBranch |
| **Slack** | Events API + Webhook | send(message), postMessage(channel, text, thread_ts), getUserName(userId) |

---

## 16. External Dependencies

| Package | Why |
| --- | --- |
| `hono` | Lightweight web framework, first-class Bun support |
| `better-queue` | In-memory queue with concurrency, dedup, retry |
| `@octokit/rest` | Official GitHub REST API client |

Built-in (no npm): fetch, crypto, bun:sqlite, Bun.file(), Bun.spawn()
