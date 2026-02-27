# TosspaperBID — Jira Epic Breakdown

> Derived from `tosspaperbid-backend-design.md`. Phases ordered by dependency.
> Timelines assume **1 senior backend developer + AI coding assistant** (Claude Code) working full-time.
> AI acceleration applies unevenly — boilerplate-heavy work (CRUD, tests, Terraform, proto stubs) sees ~50–60% speedup. Design-heavy work (architecture decisions, debugging cross-service issues) sees ~20–30% speedup.
> Calendar weeks are relative (W1 = sprint 1 start).
>
> **Backward compatibility rule:** After merging any epic, the existing `everything` service must build, deploy, and pass all tests. No epic leaves the system broken. Pattern: **add new → switch over → remove old**.

### Architecture Decision — Monolith-First

Epics 1–6 planned a multi-service split (precon-service, ai-service, email-service). Instead, precon features are being built directly in `libs/api-tosspaper` within the existing `everything` monolith. This defers infrastructure complexity and keeps deployment simple. Epics 1–6 are **skipped/deferred** — the relevant work (DB migrations, SQS queues, S3 buckets, Terraform) was done incrementally as needed.

### Current Status (2026-02-25)
- ✅ **Epic 0** — OpenAPI spec (`specs/precon/openapi-precon.yaml` v0.6.0)
- ⏭️ **Epics 1–6** — Skipped (monolith approach)
- ✅ **Epic 7A** — Tender CRUD API (5 endpoints, full test coverage)
- 🔶 **Epic 7B** — TenderDocuments done (4 endpoints), Extractions not started (7 endpoints)
- ❌ **Epics 7C–7E, 8–10** — Not started

---

## Epic 0: Precon API Spec (unblocks FE) ✅ DONE
**Phase 0 | W1 (0.5 weeks) — start immediately, no code dependencies**

Define the full OpenAPI contract for precon-service so frontend can start building against it while backend works through Epics 1–6. Just a YAML file + CI workflow — no Gradle module, no controllers, no DB, no auth.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 0.1 | Write `specs/precon/openapi-precon.yaml` + CI publish workflow | Write full OpenAPI spec covering all 11 controllers: tenders CRUD, tender_documents upload/list, bid_information approve/edit, trade_packages CRUD, rfqs create/list/add-recipients, vendor_quotes submit/list/select, bid_leveling trigger/view/award, tender_events CRUD, checklist_items CRUD, dashboards (unified GC + vendor view), SSE stream, extractions review. All schemas with field types, validation constraints, enums. Add GitHub Actions workflow: on changes to `specs/precon/**`, run `openapi-generator-cli generate -g typescript-axios` (`withInterfaces=true,withSeparateModelsAndApi=true`), bump version, `npm publish` as `@tosspaper/precon-api-client` | L |

**Backward compatibility:** Purely additive — new directory with YAML file, new CI workflow. Zero changes to existing code. Not a Gradle module — api-precon runs its own `openApiGenerate` task pointing at `$rootDir/specs/precon/openapi-precon.yaml`.

**Acceptance:**
- `./gradlew test` passes — no existing code affected
- `specs/precon/openapi-precon.yaml` covers all 11 controllers from the design doc
- CI generates and publishes `@tosspaper/precon-api-client` to private npm registry on spec changes
- FE can `npm install @tosspaper/precon-api-client` and import typed Axios API classes + models
- Spec reviewed by FE lead for field naming, pagination format, error response shape

**AI leverage:** Very high — AI drafts the full spec from the design doc table/endpoint definitions. Human reviews field names, validation constraints, and pagination/error conventions with FE team.

**Blocked by:** Nothing (just needs the design doc)
**Blocks:** FE development, Epic 7 (api-precon implements these interfaces)

---

## Epic 1: Infrastructure Foundation ⏭️ SKIPPED (monolith approach)
**Phase A | W1 (1 week)**

Sets up the build system, Docker, and SQS queues so all subsequent work has a place to land.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 1.1 | Update settings.gradle with all new modules | Add libs/auth, libs/tosspaper-jooq, libs/precon-jooq, libs/ai-grpc-client, libs/tosspaper-grpc-client, libs/email-client, libs/api-precon, services/precon-service, services/ai-service, services/email-service. All new modules are empty stubs with minimal build.gradle — nothing compiles against them yet. API specs are plain directories (`specs/`), not Gradle modules | S |
| 1.2 | Clean up root build.gradle | Remove AWS/Redis deps from subprojects block — not every lib needs them. Verify each existing lib still compiles after removal | S |
| 1.3 | Add precon PostgreSQL to Docker Compose | Second Postgres container for precon DB. Update docker-compose.yml. Existing postgres container unchanged | S |
| 1.4 | Add service containers to Docker Compose | precon-service (:8082), ai-service (:9090), email-service (:8081). These are additive — `everything` service config untouched | M |
| 1.5 | Create SQS queues | rfq-outbound, notifications, reminders, precon-events. Update LocalStack init script. Existing queues untouched | M |

**Backward compatibility:** All changes are additive. New modules are empty stubs. Existing everything service is untouched. `./gradlew :services:everything:build` passes before and after.

**Acceptance:**
- `./gradlew :services:everything:build` passes — no existing code affected
- `./gradlew :services:everything:test` passes — all existing tests green
- `docker-compose up` starts all 4 services + 2 DBs + SQS queues
- New modules are empty stubs with valid build.gradle files

**AI leverage:** High — build configs, Docker Compose, LocalStack scripts are boilerplate-heavy. AI generates most of it.

**Blocked by:** Nothing
**Blocks:** Epics 2, 3, 4, 5, 6, 7, 8

---

## Epic 2: Database Separation ⏭️ SKIPPED (monolith approach)
**Phase B | W1–W2.5 (1.5 weeks)**

Split databases, create JOOQ libs, make models pure domain. Must complete before any module compiles against JOOQ classes.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 2.1 | Move Flyway migrations to `flyway/tosspaper/` | Move existing files from `flyway/` to `flyway/tosspaper/`. **Atomic change** — in the same PR, update ALL references: `libs/models/build.gradle` (locations), `libs/ai-engine/build.gradle` (srcDirs), `libs/email-engine/build.gradle` (srcDirs), `libs/api-tosspaper/build.gradle` (srcDirs + processTestResources), `services/everything/build.gradle` (processTestResources), `docker-compose.yml` (flyway volume mount), `.github/workflows/flyway.yml` (paths trigger, `-locations`, S3 sync path), `Makefile` (if applicable). Must be one commit — partial move breaks CI | L |
| 2.2 | Write precon Flyway migrations | Create `flyway/precon/` with all 10 tables: tenders, bid_information, tender_documents, trade_packages, rfqs, rfq_recipients, vendor_quotes, bid_leveling, tender_events, checklist_items. Additive — doesn't touch tosspaper migrations | L |
| 2.3 | Create libs/tosspaper-jooq | **Copy** (not move) JOOQ plugin, Testcontainers setup, Flyway-driven generation from libs/models into new lib. Points at `flyway/tosspaper/`. At this point both models and tosspaper-jooq generate JOOQ classes (temporary duplication) | L |
| 2.4 | Switch consumers to tosspaper-jooq | Update ai-engine, email-engine, api-tosspaper to depend on tosspaper-jooq instead of models for JOOQ classes. Verify compilation + tests | M |
| 2.5 | Strip JOOQ from libs/models | Remove JOOQ generation task and Testcontainers setup from models. Safe now because all consumers switched in 2.4. Keep domain classes, exceptions, JSON schema POJOs | M |
| 2.6 | Create libs/precon-jooq | Same pattern for precon DB. JOOQ plugin + Testcontainers + Flyway pointing at `flyway/precon/`. Purely additive — no existing module depends on it yet | M |

**Backward compatibility:** Story 2.1 is the only risky one — must be atomic (file move + all reference updates in one commit). Stories 2.3–2.5 follow the "copy → switch → remove" pattern: JOOQ generation exists in both models and tosspaper-jooq temporarily, consumers switch over, then old code is removed. At every intermediate step, `./gradlew build` passes.

**Acceptance:**
- `./gradlew build` passes at every intermediate step (2.1 through 2.6)
- `./gradlew test` passes — all existing tests green after each story
- JOOQ classes generate from both DBs (tosspaper-jooq + precon-jooq)
- libs/models has zero JOOQ references, Testcontainers setup, or Flyway config
- CI flyway workflow triggers on `flyway/tosspaper/**` path changes

**AI leverage:** High — Flyway DDL, build.gradle configs, dependency rewiring are repetitive. AI writes migrations from the design doc table definitions directly. Human reviews schema design.

**Blocked by:** Epic 1
**Blocks:** Epics 5, 6, 7

---

## Epic 3: Shared Auth Library ⏭️ SKIPPED (monolith approach)
**Phase C1 | W2–W3 (1 week)**

Extract auth so both API modules can use it. **Must follow copy → switch → remove pattern** to stay backward compatible.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 3.1 | Create libs/auth — copy auth code | **Copy** (not move) SecurityFilterChain, JWT decoder, JWKS caching, OAuth2, CORS, user context from api-tosspaper into libs/auth. api-tosspaper keeps its own copy — both exist. libs/auth compiles independently. No existing code changes | L |
| 3.2 | Switch api-tosspaper to libs/auth | Add libs/auth as dependency. Update imports in api-tosspaper to use libs/auth classes instead of local copies. Run all controller tests to verify auth still works | M |
| 3.3 | Remove duplicate auth from api-tosspaper | Delete the original auth classes from api-tosspaper. Safe because 3.2 already switched all imports. Verify no dangling references | S |
| 3.4 | Verify test resources (JWKS, test tokens) | Ensure test JWT/JWKS fixtures are accessible from libs/auth's test classpath. Update BaseIntegrationTest if needed | M |

**Backward compatibility:** At every step, everything boots and authenticates correctly. 3.1 is purely additive (new lib, no changes to existing code). 3.2 switches imports but auth behavior is identical (same code, different package). 3.3 removes dead code only after consumers have switched. Never a moment where auth is broken.

**Acceptance:**
- `./gradlew test` passes — all existing controller + auth tests green
- api-tosspaper authenticates using libs/auth classes
- Zero security config classes remain in api-tosspaper
- libs/auth compiles and tests independently

**AI leverage:** Medium — extracting existing code is mostly mechanical (copy + rewire), but verifying auth behavior requires human judgment. AI handles the file copies and import updates.

**Blocked by:** Epic 1
**Blocks:** Epics 5, 6, 7

---

## Epic 4: Email Client Library ⏭️ SKIPPED (monolith approach)
**Phase C2 | W2–W2.5 (0.5 weeks)**

Async email interface. Can run in parallel with Epics 2 and 3.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 4.1 | Create libs/email-client — command DTOs | Define SendRfqCommand, SendNotificationCommand, SendReminderCommand | S |
| 4.2 | Create libs/email-client — SQS publisher | Implement publisher that serializes commands and sends to appropriate SQS queue | M |
| 4.3 | Write unit tests for email-client | Test serialization, queue routing, error handling | M |

**Backward compatibility:** Purely additive — new lib, no existing code changes. Nothing depends on email-client yet.

**Acceptance:**
- `./gradlew test` passes — no existing tests affected
- email-client unit tests pass (serialization, queue routing, error handling)
- email-client can publish typed commands to SQS queues

**AI leverage:** Very high — DTOs, SQS publisher, and unit tests are pure boilerplate. AI writes ~90% of this epic. Human reviews DTO field names and queue routing.

**Blocked by:** Epic 1 (SQS queues exist)
**Blocks:** Epics 5, 6, 7

---

## Epic 5: gRPC Protos + Client Libraries ⏭️ SKIPPED (monolith approach)
**Phase D | W3–W4 (1.5 weeks)**

Define service contracts and generate client stubs.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 5.1 | Write ai-service proto definitions | Services: DocumentClassification, Extraction, Conformance, Comparison, BidLeveling. Request/response messages | L |
| 5.2 | Write everything proto definitions | Services: CompanyLookup, ContactLookup, ContactValidation | M |
| 5.3 | Create libs/ai-grpc-client | Generate Java stubs from ai-service protos. Client wrapper with channel management, retries | L |
| 5.4 | Create libs/tosspaper-grpc-client | Generate Java stubs from everything protos. Client wrapper for company/contact lookups | M |
| 5.5 | Write new JSON extraction schemas | bid_instructions, schedule_of_quantities, specifications schemas. Add to schema-prompts/ | L |

**Backward compatibility:** Purely additive — new libs and schema files, no existing code changes. Nothing depends on these clients yet.

**Acceptance:**
- `./gradlew test` passes — no existing tests affected
- ai-grpc-client and tosspaper-grpc-client compile with generated stubs
- JSON extraction schemas validate against sample bid documents

**AI leverage:** High for proto file generation and build configs. Medium for JSON extraction schemas — AI drafts from design doc field lists, but human must validate against real bid documents.

**Blocked by:** Epic 1
**Blocks:** Epics 6, 7

---

## Epic 6: Refactor Existing Modules ⏭️ SKIPPED (monolith approach)
**Phase E | W4–W6 (2 weeks)**

Add gRPC servers, extract API spec, and wire new libs into everything. High-risk — touches production code. **Each story must leave everything deployable.**

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 6.1 | Move openapi.yaml to `specs/tosspaper/` | **Copy** openapi.yaml from api-tosspaper into `specs/tosspaper/openapi.yaml`. Update api-tosspaper's `openApiGenerate` inputSpec to point at new path. api-tosspaper keeps its own copy temporarily — both locations work | S |
| 6.2 | Remove old openapi.yaml from api-tosspaper | Delete inline openapi.yaml from api-tosspaper now that codegen points at `specs/tosspaper/`. Verify controllers still compile | S |
| 6.3 | Auth already switched (Epic 3) | No action needed — Epic 3 already moved api-tosspaper to libs/auth. Verify auth still works after Epic 3 if not already done | — |
| 6.4 | Add gRPC server to ai-engine | Add ExtractionService, ComparisonService, LevelingService gRPC server **alongside** existing in-process API. ai-engine now has both interfaces — existing callers unaffected | L |
| 6.5 | Add gRPC server to api-tosspaper | Add CompanyService, ContactService gRPC server. Purely additive — new endpoints, existing REST untouched | L |
| 6.6 | Wire gRPC server into everything | Add gRPC server on :9091 to everything. Expose the api-tosspaper and ai-engine gRPC services. Additive — REST :8080 unchanged | M |
| 6.7 | Add email-client alongside Mailgun | Add email-client dependency to api-tosspaper. Wire SQS publisher **alongside** existing direct Mailgun calls (dual-write: sends via both old + new path, or feature flag). Verify emails still send | M |
| 6.8 | Remove direct Mailgun from api-tosspaper | Once email-client path verified, remove direct Mailgun dependency and old sending code. email-client is now the only path | M |
| 6.9 | Keep ai-engine in-process for now | Do NOT remove direct ai-engine dependency from everything yet. Removing it requires ai-service (Epic 8) to be running. Mark for removal in Epic 8 when ai-service exists | — |
| 6.10 | Regression test everything | Run full test suite after each story. Fix any breakage | L |

**Backward compatibility:** Every story is safe:
- 6.1: Copy, don't move (parallel generation)
- 6.2: Switch imports, then delete old copy
- 6.4–6.6: Additive (new gRPC servers alongside existing interfaces)
- 6.7–6.8: Dual-write then cutover (Mailgun → email-client)
- 6.9: ai-engine stays in-process until ai-service exists in Epic 8 — **no premature removal**

**Acceptance:**
- `./gradlew test` passes after every story — all existing tests green at all times
- everything boots with all existing functionality intact
- gRPC server running on :9091 (company/contact lookups)
- Email sends via email-client (SQS), Mailgun code removed
- ai-engine still called in-process (gRPC cutover happens in Epic 8)
- OpenAPI generation points at `specs/tosspaper/openapi.yaml`

**AI leverage:** Low-medium — this is the riskiest epic. AI handles mechanical moves (file copies, import rewiring, gRPC server boilerplate) but the human must carefully verify nothing breaks. Regression testing is critical. Least compressible epic.

**Blocked by:** Epics 2, 3, 4, 5
**Blocks:** Epic 7 (precon-service needs everything gRPC)

---

## Epic 7: BID Module — api-precon 🔶 IN PROGRESS
**Phase F | W6–W10 (4 weeks)**

The core new functionality. Largest epic — break into sub-epics by domain area. This is where AI assistance has the highest absolute impact: CRUD controllers, services, repositories, MapStruct mappers, OpenAPI spec writing, and test generation are all high-volume boilerplate that AI handles well. Human focuses on business logic edge cases and cross-service integration correctness.

### Sub-epic 7A: Foundation (W6–W6.5) ✅ DONE

| # | Story | Description | Est | Status |
|---|-------|-------------|-----|--------|
| 7A.1 | Create libs/api-precon — module scaffold | Built in `libs/api-tosspaper` (monolith approach). OpenAPI codegen via `libs/openapi-precon` v0.6.0 pointing at `specs/precon/openapi-precon.yaml`. Generated models in `com.tosspaper.precon.generated.model.*` | M | ✅ |
| 7A.2 | Implement TendersController + TenderService | CRUD (create, get, list, update, delete), status transitions (pending → submitted → won/lost, pending → cancelled), search/filter, ETag optimistic concurrency, company-scoped via X-Context-Id | L | ✅ |
| 7A.3 | Write tests for Tenders | TenderControllerSpec, TenderServiceSpec, TenderRepositorySpec — all passing | L | ✅ |

### Sub-epic 7B: Documents + Extraction (W6.5–W7.5) — IN PROGRESS

| # | Story | Description | Est | Status |
|---|-------|-------------|-----|--------|
| 7B.1 | Implement TenderDocumentsController + service | Presigned URL upload/download, list with cursor pagination, delete, S3 → SQS upload pipeline with magic byte validation | L | ✅ |
| 7B.2 | Implement ExtractionsController + service | List extraction results, get detail, edit/verify fields. Read from ai-service via gRPC | L | ❌ |
| 7B.3 | Implement BidInformationController + service | Approve extracted bid info → create bid_information row. Edit fields. Promote key fields to tenders table | L | ❌ |
| 7B.4 | Write tests for Documents, Extractions, BidInfo | TenderDocumentControllerSpec, TenderDocumentServiceSpec, TenderDocumentRepositorySpec, DocumentUploadHandlerSpec, DocumentUploadProcessorSpec — all passing. Extraction tests not yet written | L | 🔶 Partial |

### Sub-epic 7C: Trade Packages + RFQs (W7.5–W8.5)

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 7C.1 | Implement TradePackagesController + service | Create from AI suggestions, edit line items (JSONB), delete. "Extraction suggests, user creates" pattern | L |
| 7C.2 | Implement RfqsController + service | Create RFQ per package, add recipients. Trigger email via email-client (SQS). Track recipient status | L |
| 7C.3 | Implement RfqRecipientsService | Add/remove vendors, track status (Sent/Viewed/Responded/Declined), update timestamps | M |
| 7C.4 | Write tests for Packages + RFQs | Integration + unit tests | L |

### Sub-epic 7D: Quotes + Leveling (W8.5–W9.5)

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 7D.1 | Implement VendorQuotesController + service | Vendor submit (multi-quote support), GC list/detail. Business guard: 10 quotes per vendor per package | L |
| 7D.2 | Implement quote selection for leveling | Auto-select latest per vendor, GC override toggle, enforce one selected per vendor per package | M |
| 7D.3 | Implement BidLevelingController + service | Trigger AI analysis via gRPC, view comparison matrix, award vendor, manual leveling (skip AI) | L |
| 7D.4 | Write tests for Quotes + Leveling | Integration + unit tests | L |

### Sub-epic 7E: Events, Checklist, Dashboard (W9.5–W10)

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 7E.1 | Implement TenderEventsController + service | Calendar events, reminders (JSONB), auto-create from extraction. Notify list | M |
| 7E.2 | Implement ChecklistItemsController + service | CRUD with audit trail (completed_by, completed_at) | M |
| 7E.3 | Implement DashboardsController + service | Unified per-company view: GC perspective (tenders owned) + vendor perspective (RFQs received) | L |
| 7E.4 | Write tests for Events, Checklist, Dashboard | Integration + unit tests | L |

**Backward compatibility:** Entirely new code — api-precon is a new module. No changes to existing everything service. Each sub-epic is independently mergeable.

**Acceptance:**
- `./gradlew test` passes — existing tests unaffected (new module only)
- All 11 controllers implemented with integration + unit tests
- OpenAPI spec (openapi-precon.yaml) complete and generates correctly
- Cross-service calls (gRPC to ai-service, gRPC to everything, SQS to email-service) wired
- All new tests pass: `./gradlew :libs:api-precon:test`

**AI leverage:** Very high — this is AI's sweet spot. For each domain area the pattern is identical: OpenAPI spec → codegen → controller → service → repository → mapper → tests. AI generates the full stack per entity, human reviews business rules and edge cases. Test generation alone (both integration + unit) saves ~40% of this epic's time.

**Blocked by:** Epics 0 (precon-api-spec), 2 (precon-jooq), 3 (auth), 5 (gRPC clients), 6 (everything gRPC server)
**Blocks:** Epic 8

---

## Epic 8: New Services + SSE
**Phase G | W10–W11 (1.5 weeks)**

Wire everything into deployable services.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 8.1 | Create services/precon-service | Spring Boot app, port 8082. Wire api-precon, auth, all client libs. Single datasource to precon DB | L |
| 8.2 | Implement SSE notification endpoint | precon-events SQS consumer → update DB → push to frontend via SSE. Internal events go direct to SSE | L |
| 8.3 | Create services/ai-service | Spring Boot app, gRPC :9090. Wire ai-engine. Datasource to tosspaper DB. Connect S3, Reducto, LLM providers | L |
| 8.4 | Create services/email-service | Spring Boot app, :8081. SQS consumer for rfq-outbound, notifications, reminders. Mailgun webhooks. Publish inbound events to precon-events queue | L |
| 8.5 | Switch everything from in-process ai-engine to ai-grpc-client | Now that ai-service (8.3) exists and runs, update everything to call ai-service via gRPC instead of in-process. Remove direct ai-engine dependency from everything. Deferred from Epic 6.9 | L |
| 8.6 | End-to-end integration test | Upload doc → classify → extract → create package → send RFQ → receive quote → level → award. All 4 services running | XL |

**Backward compatibility:** Stories 8.1–8.4 are additive (new services, nothing changes in everything). Story 8.5 is the deferred cutover from Epic 6.9 — only safe now because ai-service exists. Story 8.6 validates the full flow.

**Acceptance:**
- `./gradlew test` passes — all existing tests green
- All 4 services boot independently with correct datasource configs
- SSE pushes real-time events from precon-events queue to frontend
- everything no longer depends on ai-engine directly (uses ai-grpc-client)
- E2E flow works: upload → classify → extract → package → RFQ → quote → level → award

**AI leverage:** High for service scaffolding (Spring Boot apps, config, health checks). Medium for SSE + SQS consumers — AI writes the wiring, human debugs cross-service integration. E2E test (8.6) is the bottleneck — mostly human-driven debugging.

**Blocked by:** Epics 6, 7
**Blocks:** Epics 9, 10

---

## Epic 9: Temporal Workflows
**Phase H1 | W11–W11.5 (0.5 weeks)**

Reminder scheduling and failure handling.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 9.1 | Implement TenderReminderWorkflow | Temporal workflow: sleep until reminder time, publish to SQS. 30-min grace window. Skip stale reminders | L |
| 9.2 | Implement reminder activity retries | 3x exponential backoff on SQS publish. Catch exhausted retries, flag for manual attention | M |
| 9.3 | Configure DLQ on reminders queue | Dead letter queue for failed messages. CloudWatch alarm on DLQ depth | S |

**Backward compatibility:** Additive — new Temporal workflows, no changes to existing workflows.

**Acceptance:**
- `./gradlew test` passes — existing tests unaffected
- Temporal reminder workflows schedule and fire correctly
- DLQ catches failed messages with CloudWatch alarm
- Grace window logic verified: sends within 30 min, skips if stale

**AI leverage:** Medium — AI writes workflow/activity boilerplate, human verifies retry/grace logic and tests against real Temporal cluster.

**Blocked by:** Epic 8
**Blocks:** Epic 10

---

## Epic 10: Terraform + CI/CD
**Phase H2 | W11.5–W13 (1.5 weeks)**

Infrastructure-as-code for all new services. Separate epic because Terraform has its own review/apply cycle.

| # | Story | Description | Est |
|---|-------|-------------|-----|
| 10.1 | Terraform — ECS task definitions | precon-service, ai-service, email-service containers. Task definitions, service configs, log groups, IAM roles | L |
| 10.2 | Terraform — RDS for precon DB | Second RDS instance for precon DB. Parameter groups, subnet groups, security groups | L |
| 10.3 | Terraform — SQS queues + DLQ | rfq-outbound, notifications, reminders, precon-events. DLQ per queue. IAM policies for producers/consumers | L |
| 10.4 | Terraform — networking + ALB | ALB target groups for precon-service (:8082). Internal NLB for gRPC (:9090, :9091). Service discovery. Health check paths | L |
| 10.5 | Terraform — secrets + config | Parameter Store entries for precon-service. DB credentials in Secrets Manager. Environment variable wiring | M |
| 10.6 | CI/CD pipeline updates | Build + deploy pipelines for 3 new services. Health checks. Rollback strategy. Staging → prod promotion | L |
| 10.7 | Terraform plan review + staging apply | Run plan, review diff, apply to staging. Validate all services boot against real infra | L |

**Backward compatibility:** Additive infrastructure — new ECS tasks, new RDS, new SQS queues. Existing everything ECS task and tosspaper RDS untouched. CI/CD pipelines for new services are separate from existing pipeline.

**Acceptance:**
- Existing everything service deploys successfully — CI/CD pipeline unchanged
- `terraform plan` shows only additive changes (no modifications to existing resources)
- All 4 services deploy to staging via CI/CD
- Health checks pass on all services
- Rollback tested on at least one new service

**AI leverage:** High — HCL modules for ECS, RDS, SQS, ALB are heavily templated and AI knows AWS patterns well. Human reviews security groups, IAM policies, and networking rules. `terraform plan` review is human-critical.

**Blocked by:** Epic 9
**Blocks:** Nothing (production release)

---

## Timeline Summary

### With AI Assistance (1 dev + AI)

```
W1   W2   W3   W4   W5   W6   W7   W8   W9   W10   W11   W12   W13
├┤                                                                    Epic 0: Precon API Spec → FE unblocked
├──┤                                                                  Epic 1: Infrastructure
 ├──────┤                                                             Epic 2: Database Separation
    ├───┤                                                             Epic 3: Auth Library
    ├┤                                                                Epic 4: Email Client
        ├──────┤                                                      Epic 5: gRPC + Schemas
              ├─────────┤                                             Epic 6: Refactor Existing
                        ├──────────────────┤                          Epic 7: BID Module (api-precon)
                                           ├──────┤                   Epic 8: New Services + SSE
                                                  ├┤                  Epic 9: Temporal Workflows
                                                   ├──────────┤      Epic 10: Terraform + CI/CD
```

**Total: ~13 weeks (3 months) for 1 developer + AI**

With 2 developers + AI, parallelism on Epics 2/3/4 and sub-epics within 7 brings it to **~9–10 weeks**.

### Comparison: Without vs With AI

| Epic | Without AI | With AI | Savings | Why |
|------|-----------|---------|---------|-----|
| 0. Precon API Spec | 1w | 0.5w | 50% | AI drafts full OpenAPI from design doc. Human reviews with FE team |
| 1. Infrastructure | 2w | 1w | 50% | Build configs, Docker Compose, LocalStack scripts — pure boilerplate |
| 2. Database Separation | 2.5w | 1.5w | 40% | DDL, build.gradle, dependency rewiring — repetitive |
| 3. Auth Library | 1.5w | 1w | 33% | Extract + rewire, but human must verify auth behavior |
| 4. Email Client | 1w | 0.5w | 50% | DTOs + SQS publisher — ~90% AI-generated |
| 5. gRPC + Schemas | 2w | 1.5w | 25% | Proto syntax is boilerplate, but schema design needs human |
| 6. Refactor Existing | 2.5w | 2w | 20% | **Highest risk** — touches production. AI helps with moves, human verifies |
| 7. BID Module | 6w | 4w | 33% | **Highest volume** — CRUD stack per entity is AI's sweet spot |
| 8. Services + SSE | 2w | 1.5w | 25% | Scaffolding is fast, cross-service debugging is human |
| 9. Temporal Workflows | 1w | 0.5w | 50% | Workflow/activity boilerplate is AI-friendly |
| 10. Terraform + CI/CD | 2.5w | 1.5w | 40% | HCL modules are heavily templated. Plan review is human-critical |
| **Total** | **22w** | **13.5w** | **39%** | |

### Where AI Helps Most

- **Test generation** — Spock specs for 11 controllers + services. AI writes ~80% of tests, human adds edge cases
- **CRUD stack generation** — controller → service → repository → mapper per entity, repeated 10 times
- **OpenAPI spec writing** — AI drafts from design doc field definitions, human reviews
- **Flyway DDL** — AI converts table definitions from design doc directly to SQL
- **Build configs** — build.gradle, proto generation, Docker Compose — highly repetitive
- **Terraform HCL** — ECS, RDS, SQS, ALB modules follow patterns AI knows well

### Where AI Helps Least

- **Epic 6 regression testing** — debugging why existing tests break after refactoring
- **Cross-service integration** — gRPC channel issues, SQS serialization mismatches, SSE connection drops
- **Extraction schema validation** — needs real bid documents to verify against
- **Business logic edge cases** — multi-quote selection rules, award flow, grace window timing
- **Architecture decisions** — already made in design doc, but any mid-build pivots are human

---

## Story Point Sizing Reference

| Size | Points | Meaning |
|------|--------|---------|
| S | 1 | Config change, simple wiring |
| M | 3 | Single class/feature, moderate complexity |
| L | 5 | Multiple classes, integration points, tests |
| XL | 8 | Cross-service, complex flows |

---

## Dependencies Graph

```
Epic 0 (API Spec) ──────────────────────────────────────── FE starts building
  │
Epic 1 (Infra)
  ├── Epic 2 (DB) ──────────┐
  ├── Epic 3 (Auth) ────────┤
  ├── Epic 4 (Email Client) ┼── Epic 6 (Refactor) ──┐
  └── Epic 5 (gRPC) ────────┘                        ├── Epic 8 (Services) ── Epic 9 (Temporal) ── Epic 10 (Terraform)
              Epic 0 + 2 + 3 + 5 ── Epic 7 (BID) ───┘
```

---

## Risk Items

| Risk | Impact | Mitigation |
|------|--------|------------|
| Refactoring everything (Epic 6) breaks production | High | Feature flags, incremental PRs, full regression suite |
| gRPC proto design changes mid-build | Medium | Freeze protos after Epic 5, version with semver |
| Precon DB schema changes during Epic 7 | Medium | Write all migrations upfront in Epic 2, minimize mid-sprint changes |
| ai-service extraction for new doc types | High | Prototype extraction prompts early (can start in Epic 5.5) |
| Cross-DB entity resolution latency | Medium | Cache company/contact lookups in precon-service, batch gRPC calls |
| Open questions (MVP scope, vendor portal, etc.) | High | Resolve before W1 — answers affect Epic 7 scope significantly |
