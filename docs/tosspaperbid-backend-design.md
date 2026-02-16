# TosspaperBID — Backend Design

---

## Why This Document Exists

TosspaperBID is a preconstruction management tool. It helps General Contractors manage the bidding process — organizing bid documents, extracting key requirements with AI, breaking scope into trade packages, sending RFQs to subcontractors, collecting and comparing vendor quotes, and making informed award decisions backed by AI analysis of scope gaps, conditions, and pricing.

It's not a bidding platform that runs auctions. It's a tool that supports the GC's preconstruction team through the entire tender lifecycle: from receiving bid documents, through vendor coordination, to awarding subcontracts.

The existing Tosspaper platform already handles AI-powered document processing for invoices, POs, and delivery notes. The extraction pipeline, email engine, auth system, and deployment infrastructure are proven and production-ready.

We're extending this with a new **api-precon** module for the preconstruction domain. However, the current architecture is tightly coupled — auth, AI, and email concerns are mixed into modules where they don't belong. To support api-precon cleanly and allow AI and email to scale independently, we need to restructure into 4 services and extract shared concerns into their own libraries.

This document covers what exists today, what needs to change, and the full design for api-precon.

---

## Current State

### What We Have Today

One monolithic service (`everything`) with 5 libraries:

```
libs/
  ├── models/         — JOOQ generated classes, entities, base classes
  ├── email-engine/   — Mailgun webhooks, email threading, sender approval
  ├── ai-engine/      — AI extraction, conformance, comparison (Reducto, Spring AI)
  ├── api-tosspaper/  — REST controllers for invoices, POs, delivery notes
  │                     ALSO contains: auth config, AI endpoints, webhooks (bloated)
  └── integrations/   — QuickBooks sync, Temporal workflows

services/
  └── everything/     — single monolith, port 8080
```

### Problems

- **api-tosspaper is doing too much** — auth, AI endpoints, REST controllers, and webhooks all in one module
- **Auth is not shareable** — Spring Security, JWT, JWKS caching buried inside api-tosspaper. Any new API module would have to duplicate it.
- **AI engine has no external interface** — it's a library called in-process. Can't scale independently for heavy PDF/LLM work.
- **Email engine is synchronous** — tightly coupled, no async interface. Sending an email blocks the request.
- **No construction bidding domain** — the entire BID platform needs to be built on top of this

### What We Have That's Reusable

| Component | BID Usage | Changes Needed |
|---|---|---|
| companies + multi-tenancy | GC / Vendor companies | None |
| authorized_users + Supabase auth | User roles | Extract to shared lib |
| company_invitations | Vendor invitations | None |
| contacts (vendor/ship-to tags) | Vendor/Sub directory | None |
| extraction_task pipeline | Bid document extraction | New document types + schemas |
| S3 + presigned URLs | Bid document storage | None |
| Email engine (Mailgun + threading) | RFQ delivery + messaging | Wrap with SQS interface |
| SQS | Async inter-service commands | New queues |
| Temporal workflows | Reminders + bid lifecycle | New workflow types |
| Full-text search (tsvector) | Search tenders, vendors | None |
| Docker Compose + Terraform | Deployment | Add 3 new containers |

---

## Where We Want To Be

### Target Architecture — 4 Services, 12 Libraries, 2 API Specs

```
specs/
  ├── tosspaper/          — (new) existing OpenAPI spec moved here, versioned independently
  │     └── openapi.yaml
  └── precon/             — (new) OpenAPI spec for BID domain. CI publishes TS client to npm
        └── openapi-precon.yaml

libs/
  ├── auth/               — (new) shared security, JWT, CORS
  ├── models/             — (existing) shared domain classes, base entities, exceptions (pure domain, no JOOQ)
  ├── tosspaper-jooq/     — (new) JOOQ generated from tosspaper DB (used by ai-engine, email-engine, api-tosspaper)
  ├── precon-jooq/        — (new) JOOQ generated from precon DB (used by api-precon)
  ├── ai-grpc-client/     — (new) gRPC stubs for calling ai-service
  ├── tosspaper-grpc-client/ — (new) gRPC stubs for calling everything
  ├── ai-engine/          — (existing, expanded) extraction + gRPC server impl
  ├── email-engine/       — (existing) Mailgun, threading
  ├── email-client/       — (new) SQS command publisher
  ├── integrations/       — (existing) QuickBooks, Temporal
  ├── api-tosspaper/      — (existing, slimmed) codegen from specs/tosspaper/openapi.yaml
  └── api-precon/         — (new) controllers + services, codegen from specs/precon/openapi-precon.yaml

services/
  ├── everything/     — REST :8080 + gRPC :9091 for tosspaper (tosspaper DB)
  ├── precon-service/ — REST :8082 for BID (precon DB)
  ├── ai-service/     — gRPC :9090
  └── email-service/  — SQS worker + Mailgun webhooks :8081
```

### What Changes

| From | To |
|---|---|
| Auth buried in api-tosspaper | Shared libs/auth used by both API modules |
| AI called in-process | AI runs as separate gRPC service, scales independently |
| Email sent synchronously | Email commands published to SQS, consumed by email-service |
| 1 monolith | 4 services (2 REST APIs, gRPC, SQS worker) |
| 0 BID tables | 10 new tables for the preconstruction domain (including bid_information) |
| 1 OpenAPI spec embedded in api-tosspaper | 2 standalone specs in `specs/` directory (not Gradle modules). CI publishes TS client to npm for FE |
| 1 extraction schema | 4+ extraction schemas (invoice, bid instructions, SoQ, specs) |
| 1 shared database | 2 databases — api-tosspaper and api-precon each own their schema |
| JOOQ centralized in libs/models | tosspaper-jooq + precon-jooq as standalone libs. Models becomes pure domain. |
| 1 Flyway migration directory | 2 Flyway directories — one per database |

---

## Key Decisions

- Tenders are separate per GC — 3 GCs bidding same project = 3 independent tenders
- A tender becomes a project when won — one entity, status drives the lifecycle (Draft → Pending → Won → In Progress → Completed)
- Reuse existing infrastructure — don't modify existing tables, use what fits naturally
- Line items stored as JSONB inside trade packages — no separate table
- Checklist is its own table — needs audit trail (who completed what, when)
- Reuse existing extraction pipeline with new document types and a prompt factory per type
- AI auto-classifies uploaded documents — GC just drops files, no manual categorization
- AI extraction produces suggestions — trade packages only created after GC reviews and confirms
- RFQs are proper REST resources — one RFQ per package, recipients tracked separately, vendors can be added later
- No event sourcing — timestamps on rows are sufficient for tracking status changes
- 4 deployed services — everything (tosspaper REST), precon-service (BID REST), ai-service (gRPC), email-service (SQS worker)
- Auth extracted to a shared library — not buried inside one API module
- SQS for async inter-service communication — no Redis in the service communication path
- Separate databases for api-tosspaper and api-precon — each owns its schema, Flyway migrations, and JOOQ generation
- JOOQ out of models — tosspaper-jooq (shared by ai-engine, email-engine, api-tosspaper) and precon-jooq (used by api-precon). Consistent pattern — JOOQ libs are standalone. Models stays pure domain.

---

## Architecture — 4 Services

The system splits into four independently deployable services:

**everything (REST :8080, gRPC :9091)** — handles HTTP requests for the existing tosspaper domain: invoices, POs, delivery notes, companies, contacts. Also exposes a gRPC server on port 9091 for internal calls from precon-service (company/contact validation). Connects to the tosspaper database only. Communicates with ai-service via gRPC and email-service via SQS.

**precon-service (REST :8082)** — handles HTTP requests for the BID domain: tenders, packages, RFQs, quotes, leveling, dashboards. Connects to the precon database only. Communicates with ai-service via gRPC (ai-grpc-client) and email-service via SQS (email-client). Resolves shared entities (companies, contacts) via gRPC to everything (tosspaper-grpc-client).

**ai-service (gRPC, port 9090)** — handles document classification, extraction, conformance, comparison, and bid leveling analysis. Connects to the tosspaper database (owns extraction_task reads/writes). Talks to Reducto/Chunkr for OCR and to LLM providers (Claude/OpenAI) for extraction and analysis. Can scale independently when PDF processing gets heavy.

**email-service (SQS worker, port 8081)** — consumes commands from SQS queues (send RFQ, send notification, send reminder). Receives inbound email via Mailgun webhooks. Publishes events back to SQS when new emails arrive. Handles all Mailgun integration.

### Communication

| From | To | How | When |
|---|---|---|---|
| everything | ai-service | gRPC | extract doc, classify (tosspaper documents) |
| everything | email-service | SQS | send notifications |
| precon-service | ai-service | gRPC | extract doc, classify, analyze bids |
| precon-service | email-service | SQS | send RFQ to vendors, send notifications |
| precon-service | everything | gRPC | resolve company/contact info (via tosspaper-grpc-client) |
| Temporal | email-service | SQS | fire scheduled reminder |
| ai-service | precon-service | SQS | extraction complete, leveling complete |
| email-service | precon-service | SQS | inbound email from vendor |
| email-service | ai-service | gRPC | new attachment, run extraction |
| Mailgun | email-service | HTTP webhook | inbound email received |

### SQS Queues

- **rfq-outbound** — precon-service tells email-service to send RFQ emails
- **notifications** — everything/precon-service tell email-service to send notification emails
- **reminders** — Temporal tells email-service to send reminder emails
- **precon-events** — single inbound queue for precon-service. All async events land here with a type field: inbound_email, extraction_complete, leveling_complete. Published by email-service and ai-service. precon-service consumes, updates its DB, and pushes to frontend via SSE. Can split into dedicated queues later if a specific event type gets noisy.

### Rate Limiting

Rate limit at the API gateway level (ALB / API Gateway) — per-user request cap (e.g., 100 requests/minute per authenticated user). No application code needed. Business-level guards (like quote submission caps) are enforced in the service layer where the domain logic lives.

---

## Module Structure

### Current State

5 libraries, 1 service. Auth is buried in api-tosspaper. AI endpoints mixed into api-tosspaper. Email engine has no async interface. Everything is one monolith. One shared database with JOOQ generation centralized in libs/models and a single root-level Flyway migration directory.

### Target State

12 libraries, 4 services, 2 API specs:

**API Specs** (plain directories, not Gradle modules):
- **specs/tosspaper/openapi.yaml** — (new location) existing OpenAPI spec moved from api-tosspaper. Versioned independently. api-tosspaper codegen points here.
- **specs/precon/openapi-precon.yaml** — (new) OpenAPI spec for BID domain. api-precon codegen points here. CI generates and publishes TypeScript client (`@tosspaper/precon-api-client`) to private npm registry for FE.

**Libraries:**
- **auth** — (new) Spring Security, JWT, Supabase JWK, CORS, user context
- **models** — (existing) shared domain classes, base entities, exceptions, JSON schema POJOs. Pure domain library, no JOOQ generation.
- **tosspaper-jooq** — (new) JOOQ generated from tosspaper DB + Flyway migrations (`flyway/tosspaper/`). Shared by ai-engine, email-engine, and api-tosspaper.
- **precon-jooq** — (new) JOOQ generated from precon DB + Flyway migrations (`flyway/precon/`). Used by api-precon.
- **ai-grpc-client** — (new) gRPC stubs and client wrapper for calling ai-service
- **tosspaper-grpc-client** — (new) gRPC stubs for calling everything (company/contact validation)
- **ai-engine** — (existing, expanded) extraction, conformance, comparison, gRPC server implementation
- **email-engine** — (existing) Mailgun, threading, sender approval
- **email-client** — (new) SQS command publisher for sending emails
- **integrations** — (existing) QuickBooks, Temporal workflows
- **api-tosspaper** — (existing, slimmed) invoices, POs, delivery notes. Codegen from `specs/tosspaper/openapi.yaml`. Also exposes gRPC service implementations for company/contact lookups.
- **api-precon** — (new) tenders, packages, RFQs, quotes, leveling, events, checklist, dashboards. Codegen from `specs/precon/openapi-precon.yaml`.

**Services:**
- **everything** — REST :8080 + gRPC :9091 for tosspaper domain. Depends on api-tosspaper + ai-grpc-client + email-client + integrations
- **precon-service** — REST :8082 for BID domain. Depends on api-precon + auth + ai-grpc-client + tosspaper-grpc-client + email-client
- **ai-service** — gRPC :9090, depends on ai-engine
- **email-service** — SQS worker + Mailgun webhooks :8081, depends on email-engine + ai-grpc-client

---

## Database Architecture — Separate Databases

api-tosspaper and api-precon each have their own PostgreSQL database. This means independent schemas, independent Flyway migrations, and independent JOOQ generation.

### Why Separate

- Modules can evolve independently — adding a precon migration doesn't touch the tosspaper schema
- Precon JOOQ generation is self-contained — not mixed into libs/models
- Cleaner ownership — each module owns its tables end-to-end
- Easier to reason about — no accidental cross-domain queries

### Layout

```
flyway/
  ├── tosspaper/     — existing migrations (moved from root flyway/)
  └── precon/        — new migrations for 10 BID tables

libs/
  ├── tosspaper-jooq/
  │     └── build.gradle  — JOOQ plugin, Testcontainers, generates from tosspaper DB
  │                         depended on by: ai-engine, email-engine, api-tosspaper
  └── precon-jooq/
        └── build.gradle  — JOOQ plugin, Testcontainers, generates from precon DB
                            depended on by: api-precon
```

### Shared Entities

companies, authorized_users, and contacts live in the tosspaper database. api-precon needs to reference them (e.g., `tenders.company_id`, `rfq_recipients.contact_id`).

Since these are cross-database references, we **cannot use database-level foreign keys**. Instead:
- api-precon stores the UUID (e.g., `company_id`) as a plain column
- The application layer validates the reference exists (via tosspaper-grpc-client gRPC calls to everything)
- No cross-database joins — api-precon queries its own DB and resolves references in the application layer

### Each Service Owns One Database

No dual datasource complexity. Each service has a single, simple datasource configuration:
- **everything** → tosspaper DB
- **precon-service** → precon DB
- **ai-service** → tosspaper DB (extraction_task lives here)
- **email-service** → tosspaper DB (email threading tables live here)

precon-service resolves shared entities (companies, contacts) via tosspaper-grpc-client (gRPC to everything on port 9091) — not by querying the tosspaper DB directly. precon-service references extraction results via ai-grpc-client (gRPC to ai-service) — not by reading extraction_task directly.

### ai-service and email-service Share tosspaper DB (v1)

Both services have existing data, migrations, and references in the tosspaper DB. Moving them means data migration, rewriting references, and restructuring Flyway history — too much risk for v1.

**ai-service** — owns extraction logic, reads/writes extraction_task in the tosspaper DB. Both everything and precon-service access extraction data through ai-grpc-client via gRPC. The data stays put, but the access pattern goes through the right service boundary.

**email-service** — owns email threading, sender approval, Mailgun webhook state in the tosspaper DB. Other services interact via SQS commands and events, not direct DB access.

### Future: Separate Databases per Service

Once stable, each service migrates its tables to its own database:
- **ai-service** → ai DB (extraction_task, extraction-related tables)
- **email-service** → email DB (email threads, sender approvals, webhook state)
- **everything** → tosspaper DB (companies, contacts, invoices, POs, delivery notes)
- **precon-service** → precon DB (already separate from v1)

The gRPC and SQS interfaces don't change — callers never knew where the data lived. Clean migration path with zero consumer impact. At that point, tosspaper-jooq splits into service-specific JOOQ libs (ai-jooq, email-jooq), same pattern as precon-jooq.

### libs/models — Pure Domain, No JOOQ

models becomes a pure domain library: shared base entities, exceptions, JSON schema POJOs. The JOOQ generation task and Testcontainers setup move out of models into libs/tosspaper-jooq.

### JOOQ Libraries

**libs/tosspaper-jooq** — takes over JOOQ generation from models. Runs Flyway against `flyway/tosspaper/` in a Testcontainer, generates JOOQ classes. ai-engine, email-engine, and api-tosspaper all depend on this.

**libs/precon-jooq** — same pattern for the precon DB. Runs Flyway against `flyway/precon/` in a Testcontainer, generates JOOQ classes. api-precon depends on this.

---

## api-precon — What It Owns

The new BID API module. Handles all construction bidding business logic.

**Entities:** tenders, bid_information, tender_documents, trade_packages, rfqs, rfq_recipients, vendor_quotes, bid_leveling, tender_events, checklist_items

**Depends on:** auth (shared security), models (shared domain classes), precon-jooq (JOOQ classes for precon DB), ai-grpc-client (calls ai-service for extraction + leveling), tosspaper-grpc-client (calls everything for company/contact validation), email-client (sends RFQ/notification commands to SQS). Codegen from `specs/precon/openapi-precon.yaml`.

**Controllers:**
- TendersController — CRUD, status transitions, search/filter
- BidInformationController — approve extracted bid info, edit fields, get approved bid info
- TradePackagesController — create from AI suggestions, edit line items
- RfqsController — create RFQ per package, manage recipients
- VendorQuotesController — vendor submits, GC reads
- BidLevelingController — trigger AI analysis, view comparison matrix, award package to vendor
- TenderEventsController — calendar, reminders
- ChecklistItemsController — tender prep checklist
- TenderDocumentsController — upload, list
- ExtractionsController — review/verify AI results
- DashboardsController — unified dashboard showing both perspectives per company

**Services:** one service class per entity, each with its own JOOQ repository and MapStruct mapper.

**OpenAPI spec:** lives in `specs/precon/openapi-precon.yaml`, not in api-precon itself. api-precon's `openApiGenerate` task points here. CI publishes TypeScript client to npm for FE. Spec versioned independently from implementation.

---

## What We're Reusing

| Component | BID Usage | Changes |
|---|---|---|
| companies + multi-tenancy | GC / Vendor companies | None |
| authorized_users + Supabase auth | User roles | Extracted to shared auth lib |
| company_invitations | Vendor invitations | None |
| contacts (vendor/ship-to tags) | Vendor/Sub directory | None |
| extraction_task pipeline | Bid document extraction | New document types + new JSON schemas |
| S3 + presigned URLs | Bid document storage | None |
| Email engine (Mailgun + threading) | RFQ delivery + messaging | Wrapped with SQS interface |
| SQS | Async inter-service commands | New queues |
| Temporal workflows | Reminders + bid lifecycle | New workflow types |
| Full-text search (tsvector) | Search tenders, vendors | None |
| Docker Compose + Terraform | Deployment | Add 2 new service containers |

---

## New Tables (10)

### 1. tenders
The root entity. Starts as a tender (bidding), becomes a project when won. Fields: company, name, client name, location, bid date, estimated value, status, assignee, description, metadata.

### 2. bid_information
The approved bid info for a tender. Created when the GC reviews and approves AI-extracted bid info — same "extraction suggests, user creates" pattern as trade_packages. One row per tender. After approval, all reads come from this table — no need to call extraction again.

Fields: tender, closing date, closing time, client name, location, start date, duration, bonds required (boolean), bond percentage, liquidated damages rate, insurance requirements (JSONB), submission method, selection criteria, contract admin, tender opening date, site walk date, site walk location, pre-bid meeting date, special conditions (JSONB), verification status (per field, JSONB).

The conformed_json in extraction_task stays as the permanent audit trail (what was extracted, from which page, with what confidence). bid_information is the approved, editable, live version the GC and vendors actually work with.

### 3. tender_documents
Links uploaded files to tenders and to the existing extraction pipeline. Fields: tender, file name, file type, document type (AI-classified), page count, file size, S3 storage key, extraction task reference.

### 4. trade_packages
Grouped scope of work by trade (Electrical, Concrete, etc). Line items stored as JSONB inside. Created only after GC reviews and confirms AI suggestions. Fields: tender, name, type (subcontractor/vendor), status, line items array.

Each line item in the JSONB array has: id, spec code, description, quantity, unit, extraction confidence.

### 5. rfqs
One RFQ per trade package. The request document sent to vendors. Fields: tender, package, subject, message (cover note from GC), due date, sent timestamp.

### 6. rfq_recipients
Who received the RFQ and their response status. New vendors can be added later to the same RFQ. Fields: RFQ, vendor contact, status (Sent/Viewed/Responded/Declined), sent/viewed/responded timestamps. Unique constraint on RFQ + vendor.

### 7. vendor_quotes
A vendor's submitted bid for a trade package. Multiple quotes allowed per vendor per package — no unique constraint on (package_id, contact_id). A vendor might email one version, submit a revision through the portal, or send two options (lump sum vs line-item breakdown). Each quote is its own record with its own submitted_at timestamp.

Fields: package, vendor contact, submission type (lump sum/line items/uploaded), total amount, discount, private note, inclusions (JSONB array), exclusions (JSONB array), notes (JSONB array), line item prices (JSONB array), uploaded file storage key, status, selected_for_leveling (boolean, default false), submitted_at.

Each line item price in the JSONB array has: line item id, unit price, total, note.

**Business-level guard:** cap of 10 quotes per vendor per package — sanity check, not a rate limit. Prevents accidental mass submission. Enforced in the service layer.

**Quote selection for leveling:** When the GC triggers leveling, the system auto-selects the latest quote per vendor (sets selected_for_leveling = true). GC can override by toggling a different quote. Only one quote per vendor per package can be selected at a time — enforced in the service layer. Leveling query: all quotes where selected_for_leveling = true for that package.

### 8. bid_leveling
The leveling record doubles as the award record. Every award goes through a bid_leveling row — either AI-generated or manually created. Fields: tender, package, source (ai/manual), condition flags (JSONB array), observations (JSONB array), scope gaps (JSONB array), bid spread (JSONB with avg/low/high/spread %), awarded vendor.

**AI leveling:** GC triggers analysis → row created with full analysis fields, source = ai. GC then sets awarded_vendor.
**Manual leveling:** GC skips analysis, awards directly → row created with empty analysis fields, source = manual, awarded_vendor set immediately.

### 9. tender_events
Calendar events with scheduled reminders. Auto-created from AI extraction or manually added by GC. Fields: tender, event type (bid closing/site walk/pre-bid meeting/rfq deadline/custom), title, event date, all-day flag, location, reminders (JSONB array with before_minutes and channel), notify list (JSONB array), status, source (extracted/manual).

Reminder intervals stored as minutes (4320 = 3 days, 1440 = 1 day, 60 = 1 hour, 15 = 15 min).

### 10. checklist_items
Tender preparation checklist with audit trail. Fields: tender, description, suggested flag, completed flag, completed by (user), completed at (timestamp).

---

## Entity Relationship

```
TOSSPAPER DB (everything + ai-service)      PRECON DB (precon-service)
───────────────────────────────────────      ─────────────────────────
companies (existing)                        tenders (NEW) ← company_id (app-level ref)
  ├── authorized_users (existing)             ├── bid_information (NEW) ← approved from extraction
  ├── contacts (existing) ························├── tender_documents (NEW)
  │                                           │     └── extraction via gRPC (not direct DB ref)
  └── extraction_task (existing)              ├── tender_events (NEW)
        ↑ owned by ai-service                 ├── checklist_items (NEW)
        ↑ accessed via gRPC by                ├── trade_packages (NEW)
          precon-service                      │     ├── rfqs (NEW)
                                              │     │     └── rfq_recipients (NEW) ← contact_id (app-level ref)
  ····· = cross-DB app-level ref              │     ├── vendor_quotes (NEW) ← contact_id (app-level ref)
  ───── = same-DB foreign key                 │     └── bid_leveling (NEW)
                                              │
                                              └── [post-award: subcontracts, change orders — future]
```

---

## AI Extraction — Prompt Factory

Different document types need different prompts. AI auto-classifies on upload — no manual categorization. The pipeline is two stages: classify first, then extract with the right prompt.

**precon-service** receives the upload, stores in S3, then calls **ai-service** via gRPC to classify and extract.

| Document Type | What It Extracts |
|---|---|
| bid_instructions | 14 bid info fields: closing date, bonds, LD rates, insurance, site walk, submission method, etc. |
| schedule_of_quantities | Line items with spec codes, quantities, units — grouped by trade as package suggestions |
| specifications | Scope sections organized by CSI division |
| drawings | Store for download only (v1). Optional v1.5: extract title blocks for auto-tagging |
| invoice | Existing extraction (unchanged) |
| delivery_slip | Existing extraction (unchanged) |
| delivery_note | Existing extraction (unchanged) |

New JSON schema files needed for bid_instructions, schedule_of_quantities, specifications, and optionally drawings.

### Extraction Flow

1. GC uploads PDFs (no categorization) → precon-service stores in S3 → tender_documents rows created
2. precon-service calls ai-service via gRPC → classify document type
3. ai-service classifies → type saved on tender_documents
4. precon-service calls ai-service via gRPC → extract using type-specific prompt
5. ai-service runs OCR (Reducto/Chunkr) → LLM extraction → conformed_json saved
6. GC reviews in UI:
   - Bid info fields: verify/edit → GC approves → **bid_information row created**
   - SoQ suggestions: review/edit packages → GC confirms → **trade_packages created**
7. Key dates auto-create tender_events (bid closing, site walk)
8. Some bid info fields (closing date, client name, location) also promote to the tenders table for search/filter

AI extraction produces **suggestions**. bid_information and trade_packages only become real resources when the GC reviews and approves. After approval, no need to call extraction again.

---

## Bid Information Lifecycle

Same "extraction suggests, user creates" pattern as trade packages:

1. AI extracts 14+ bid info fields into extraction_task.conformed_json (value, source page, confidence, verification status per field)
2. GC reviews in UI — edits, corrects, verifies
3. GC approves → **bid_information row created** with all approved fields as proper columns
4. Key dates (closing, site walk, pre-bid meeting) also auto-create tender_events
5. Some fields (closing date, client name, location) also promote to the tenders table for search/filter

After approval, all reads come from **bid_information** — no need to query extraction_task again. The conformed_json stays as the permanent audit trail showing what was extracted, from which page, with what confidence. bid_information is the live, editable version the GC and vendors work with.

---

## Reminder System

Uses Temporal (already deployed) for scheduling. When AI extracts a key date (e.g., "Bid Closing: Feb 12 2:00 PM"), a tender_events row is created with reminders at 3 days, 1 day, 1 hour, and 15 minutes before.

A Temporal workflow starts per event, sleeps until each reminder time, wakes up, and publishes a command to SQS. The email-service consumes the command and sends the notification via Mailgun.

### Failure Handling

**Activity retries:** the SQS publish is a Temporal activity with a retry policy — 3 attempts with exponential backoff. If all retries exhaust, Temporal marks the activity as failed. The workflow catches that and either skips to the next reminder or flags it for manual attention.

**Dead letter queue:** DLQ on the reminders SQS queue for messages that email-service fails to process. Gives visibility into reminders that were published but never sent.

**Missed reminder recovery:** on wake-up, check if the reminder time is still in the future or within a 30-minute grace window. If yes, send it. If the window passed, skip it and log it. Sending a "bid closes in 3 days" reminder 2 days late is noise — sending a "bid closes in 1 hour" reminder 30 minutes late is still useful.

---

## API Endpoints

All resources are plural. Actions are modeled as resource creation.

| Area | Endpoints |
|---|---|
| **Tenders** | list, create, get, update |
| **Bid Information** | get (approved), approve from extraction, edit fields |
| **Checklist** | list items, create item, toggle/update item |
| **Documents** | list, upload (multipart, no type needed) |
| **Extractions** | list results, get detail, edit/verify fields |
| **Trade Packages** | list, create (confirm suggestions), update, delete |
| **RFQs** | list, create (triggers email via SQS), get detail, add recipients |
| **Quotes** | list per package (GC view), submit (vendor view), get detail, select quote for leveling |
| **Leveling** | get comparison matrix, trigger AI analysis (gRPC to ai-service), award vendor (sets awarded_vendor on leveling record), create manual leveling (skip AI, award directly) |
| **Events** | list per tender, create, update/dismiss, list upcoming across tenders |
| **Dashboards** | single dashboard per company — GC perspective (tenders owned: active/won/lost/bond exposure) + vendor perspective (RFQs received: action required/submitted/won/pipeline). Same company sees both. |
| **Notifications** | SSE stream — real-time events pushed to frontend |

### SSE Notification Flow

The frontend holds an SSE connection to precon-service. Events originate across all services but funnel through the single **precon-events** SQS queue:

1. **ai-service** publishes `extraction_complete` or `leveling_complete` to precon-events
2. **email-service** publishes `inbound_email` to precon-events
3. **precon-service** consumes the queue, updates its database, and pushes the event to the frontend over SSE

Events that originate within precon-service itself (quote submitted via portal, award made) go directly to the SSE stream — no SQS round-trip needed.

---

## Drawings / Specs — AI Strategy

| Document | v1 Approach |
|---|---|
| Bid instructions (PDF text) | Full extraction via existing pipeline |
| Schedule of Quantities (PDF/Excel) | Extract + suggest trade packages (user confirms) |
| Specifications (PDF text) | Extract by CSI division |
| Drawings (visual/CAD) | **Store for download only** — vendors do takeoff in Bluebeam/PlanSwift |

Drawings are visual/spatial — current AI pipeline reads text, not floor plans. Optional v1.5: extract title blocks using vision models to auto-tag sheets to trade packages.

---

## Required Changes

Phases are ordered by dependency — each phase only depends on phases before it.

### Phase A: Settings + Infrastructure Foundation

**A1. settings.gradle** — add all new modules: libs/auth, libs/tosspaper-jooq, libs/precon-jooq, libs/ai-grpc-client, libs/tosspaper-grpc-client, libs/email-client, libs/api-precon, services/precon-service, services/ai-service, services/email-service. API specs live in `specs/` directory (not Gradle modules).

**A2. Clean up root build.gradle** — remove AWS and Redis deps from the subprojects block. Not every lib needs them.

**A3. Docker Compose** — add precon-service (port 8082), ai-service (port 9090), and email-service (port 8081) containers. Add second PostgreSQL database for precon. Update LocalStack to create new SQS queues.

**A4. SQS queues** — create 4 queues (rfq-outbound, notifications, reminders, precon-events). Update LocalStack init script for local dev.

### Phase B: Database Separation

Must happen before any module can compile against its own JOOQ classes.

**B1. Create precon database** — new PostgreSQL database (local Docker, RDS in prod).

**B2. Split Flyway migrations** — move existing migrations from root `flyway/` to `flyway/tosspaper/`. Create new `flyway/precon/` directory for api-precon's 10 tables. Each directory targets its own database.

**B3. Create libs/tosspaper-jooq** — new library. Move the JOOQ plugin, Testcontainers setup, and Flyway-driven generation out of libs/models into this lib. Points at the tosspaper database and `flyway/tosspaper/` migrations. ai-engine, email-engine, and api-tosspaper depend on this.

**B3b. Create libs/precon-jooq** — same pattern. JOOQ plugin + Testcontainers + Flyway pointing at the precon database and `flyway/precon/` migrations. api-precon depends on this.

**B4. Update libs/models** — remove JOOQ generation task and Testcontainers setup. Keep shared domain classes, base entities, exceptions, and JSON schema POJOs. Models becomes a pure domain library. Update ai-engine, email-engine, and api-tosspaper to depend on tosspaper-jooq for JOOQ classes.

**B5. Shared tables** — companies, authorized_users, contacts live in the tosspaper database. api-precon's tenders table uses `company_id` (UUID) without a DB-level foreign key — the application layer enforces the reference. This avoids cross-database joins while keeping the data clean.

### Phase C: Extract Auth + Create New Libs

**C1. Create libs/auth** — extract Spring Security, OAuth2, JWKS caching, CORS, and user context from api-tosspaper into a shared library that both api-tosspaper and api-precon can depend on.

**C2. Create libs/email-client** — define command DTOs for sending RFQs, notifications, and reminders. Implement a publisher that sends these to SQS queues.

### Phase D: Proto + Schemas → Client Libs

Proto definitions and JSON schemas must exist before client libs can generate stubs and before api-precon can reference extraction schemas.

**D1. gRPC proto files for ai-service** — define services for extraction, conformance, comparison, and leveling.

**D2. gRPC proto files for everything** — define services for company lookup, contact lookup, contact validation. These are the internal APIs that precon-service calls.

**D3. JSON extraction schemas** — write new schema files for bid_instructions, schedule_of_quantities, specifications, and optionally drawings.

**D4. Create libs/ai-grpc-client** — generate Java stubs from ai-service proto definitions. Create a client wrapper for channel management.

**D5. Create libs/tosspaper-grpc-client** — generate Java stubs from everything proto definitions. Client wrapper for company/contact lookups.

### Phase E: Refactor Existing Modules

Depends on auth, ai-grpc-client, tosspaper-grpc-client, and email-client existing from C and D.

**E0. Move openapi.yaml to specs/tosspaper/** — move existing `openapi.yaml` from api-tosspaper to `specs/tosspaper/openapi.yaml`. Update api-tosspaper's `openApiGenerate` inputSpec to point at new path. Not a Gradle module.

**E1. Slim down api-tosspaper** — remove auth config (now in libs/auth), remove AI/extraction endpoints (move to ai-engine), remove Svix webhook verification (move to integrations), delete the ApiTossPaperApplication main class, remove OpenAPI spec (now in `specs/tosspaper/`). Replace direct ai-engine dependency with ai-grpc-client. Replace direct Mailgun dependency with email-client. Add gRPC server implementations for company/contact services.

**E2. Update ai-engine** — add gRPC server implementations for extraction/comparison/leveling. Move extraction and comparison REST endpoints here from api-tosspaper.

**E3. Update everything** — remove inline security deps (gets them from auth lib). Add deps on auth, ai-grpc-client, email-client. Add gRPC server on port 9091 (serves tosspaper-grpc-client protos). Remove direct ai-engine dependency. No longer depends on api-precon — that moves to precon-service.

### Phase F: Create BID Module

Depends on precon database + Flyway + JOOQ (Phase B) and shared libs (Phases C-D).

**F1. Write specs/precon/openapi-precon.yaml** — define all BID endpoints. Not a Gradle module. CI publishes TypeScript client to npm for FE. Done early (before Phase F) to unblock frontend.

**F2. Create libs/api-precon** — implement controllers (against codegen from `specs/precon/openapi-precon.yaml`), services, and repositories (against precon-jooq classes). Implement MapStruct mappers.

### Phase G: Create New Services

Depends on all libraries being ready.

**G1. Create services/precon-service** — a new Spring Boot app for the BID REST API on port 8082. Depends on api-precon, auth, ai-grpc-client, tosspaper-grpc-client, email-client. Connects to the precon database only. Resolves shared entities via tosspaper-grpc-client (gRPC to everything :9091).

**G2. Create services/ai-service** — a new Spring Boot app that runs the gRPC server on port 9090. Needs access to S3, PostgreSQL, Reducto/Chunkr, and LLM providers.

**G3. Create services/email-service** — a new Spring Boot app that consumes SQS queues and receives Mailgun webhooks on port 8081. Publishes inbound email events back to SQS. Needs access to Mailgun, S3, and PostgreSQL.

### Phase H: Workflows + Deployment

**H1. Temporal workflows** — implement TenderReminderWorkflow that sleeps until reminder time and publishes to SQS.

**H2. Terraform** — add ECS task definitions for precon-service, ai-service, and email-service. Add SQS queue resources. Add second RDS instance for precon DB. Update security groups for gRPC port and precon-service port.

---

## Design Decisions Log

| Decision | Rationale |
|---|---|
| Tenders separate per GC | Simplicity — no shared project identity needed |
| One tenders table for full lifecycle | Tender to Project is a status change, not a migration |
| Line items as JSONB | Avoids 2 extra tables, line items rarely queried independently |
| Checklist as separate table | Needs audit trail (completed_by, completed_at) |
| Reuse extraction_task | Same pipeline, just new schemas and prompt factory |
| bid_information as its own table | After GC approves, no need to call extraction again. conformed_json stays as audit trail. |
| precon-service calls ai-service (not everything) | Bid documents are the precon domain — precon-service owns the upload and extraction flow |
| AI auto-classifies documents | Better UX — GC just drops files |
| Extraction suggests, user creates | GC must review before packages become real resources |
| RFQs as resources | Supports adding vendors later, proper REST, trackable status |
| Separate rfq_recipients table | Need to query by vendor status across RFQs |
| No event sourcing | Linear status progression, timestamps suffice |
| Reminder intervals as minutes | No string parsing — integer math for scheduler |
| Auth as shared lib | Both API modules need it, eliminates duplication |
| ai-service via gRPC | Independent scaling for heavy PDF/LLM processing |
| email-service via SQS | Fire-and-forget async, no blocking the REST API |
| Separate databases per API module | Independent schemas, independent Flyway, independent JOOQ — modules don't step on each other |
| JOOQ generation out of models | tosspaper-jooq for tosspaper DB, precon-jooq for precon DB. Consistent pattern — JOOQ libs are standalone, API libs consume them. Models stays pure domain. |
| API specs as plain directories | `specs/tosspaper/` + `specs/precon/` — OpenAPI YAML files versioned independently, not Gradle modules. Each API lib runs its own codegen pointing at the spec. CI publishes TypeScript client to npm for FE. |
| Shared entities via app-level refs | company_id stored as UUID without cross-DB FK — application enforces, avoids cross-DB joins |
| Multiple quotes per vendor per package | No unique constraint on (package_id, contact_id) — vendors can revise, send options |
| selected_for_leveling boolean on quotes | Simpler than JSONB array on bid_leveling — flat field, no joins, toggle override |
| Default to latest quote, allow override | Happy path is automatic, multi-quote scenario needs no extra clicks |
| bid_leveling is also the award record | Every award (AI or manual) creates a leveling row — source field distinguishes |
| No separate awards table | Award is just awarded_vendor on bid_leveling — one place to query "who won" |
| Unified dashboard per company | Same company can be GC on some tenders and vendor on others — one view, both perspectives |
| Reminder DLQ on SQS | Visibility into reminders published but never sent by email-service |
| 30-min grace window for missed reminders | Send if still useful, skip if stale — avoids spamming old reminders |
| Temporal activity retries (3x exponential) | SQS publish failures retry automatically before escalating |
| Single precon-events queue with typed messages | Simpler at v1 — one consumer, one SQS resource. Split later if noisy |
| SSE from precon-service only | Frontend connects to one service. Cross-service events arrive via SQS first |
| extraction_task stays in tosspaper DB | Existing data, migrations, references — too much risk to move at v1 |
| ai-service + email-service share tosspaper DB at v1 | Existing data, migrations, references — too much risk to move now. Access goes through gRPC/SQS boundaries already |
| 2 databases at v1 (tosspaper + precon) | Future: 4 databases (tosspaper, precon, ai, email). gRPC/SQS interfaces won't change — clean migration |
| Rate limit at API gateway, not app code | ALB/API Gateway handles per-user caps — no Spring filters needed at v1 |
| Business-level quote cap (10 per vendor per package) | Sanity check, not rate limiting — prevents accidental mass submission |

---

## Open Questions for Call

1. **What's the MVP flow?** Upload → extract → packages → RFQs → quote → leveling? Or smaller?
2. **Which AI features in v1?** All three (extraction, auto-packages, leveling) or just extraction?
3. **Vendor portal in v1?** Or vendors just receive emails initially?
4. **Where do vendors come from?** GC types emails, or import from directory?
5. **Supabase project setup?** Separate Supabase project for precon DB, or same project with two databases?
6. **Who's building frontend?** React/Next.js/mobile?
7. **Per-GC adjustments in v1?** Unique differentiator but adds complexity.
8. **Drawings — just store, or extract title blocks?**
9. **Who's the first pilot user?** Their workflow defines priority.
10. **Vendor onboarding flow?** When a GC sends an RFQ to someone without an account, how does that vendor sign up and get linked to the existing contact + rfq_recipients row? Needs at minimum: invitation link in RFQ email, signup path, account-to-contact linking logic.