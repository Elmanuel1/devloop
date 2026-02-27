# Open Questions — RESOLVED 2026-02-25

All questions have been answered by the user via Confluence feedback.
Decisions are incorporated into `03-architect-plan.md` (revised version).

---

## Q1: Tender Status + Extraction Interaction — RESOLVED

### At extraction creation time

| Tender Status | Allow extraction? | Decision |
|--------------|-------------------|----------|
| `pending` | Yes | CONFIRMED |
| `submitted` | Yes | CONFIRMED |
| `won` | No | BLOCKED — `api.extraction.tenderNotActive` |
| `lost` | No | BLOCKED — `api.extraction.tenderNotActive` |
| `cancelled` | No | BLOCKED — `api.extraction.tenderNotActive` |

Implementation: `TenderExtractionAdapter.verifyOwnership` checks tender status after
company ownership. Only `pending` and `submitted` are allowed.

### During extraction processing

| Event | Decision |
|-------|----------|
| Tender gets `cancelled` | Check Reducto cancel API. If available, cancel outstanding jobs and mark extraction `cancelled`. |
| Tender gets `won`/`lost` | Block the status transition until extraction completes. `TenderServiceImpl.updateTender` checks for in-progress extractions. |
| Document deleted mid-extraction | Block document deletion. `TenderDocumentServiceImpl` checks for processing extractions before deleting. |

### After extraction completes

| Event | Decision |
|-------|----------|
| Tender gets `cancelled` | Leave extraction data as historical record. No cascade. |
| Document gets deleted | Leave orphaned citations. UI shows "source deleted" label. |

---

## Q2: Classification Model Choice — RESOLVED

Use existing `classifierChatModel` bean (GPT-4o-mini). Cost is ~$0.0015 for 50 docs.
Acceptable.

---

## Q3: ExtractionFieldsApi Separation — RESOLVED

Yes. Split field endpoints into `ExtractionFieldsApi` separate controller in a **next PR**
(spec-writer task). Not part of the current pipeline PRs (#1–#4).

---

## Q4: Foundation PR Migration Version — RESOLVED

V3.6 already exists on both the feature branch and on `main`.
V3.7 is the correct next version for the pipeline_state migration.

---

## Q5: Reducto Schema for Tender Fields — RESOLVED

Code-writer generates schema from `TenderFieldName` enum. Schema JSON is provided in
`03-architect-plan.md` PR #3 section. Located at:
`schema-prompts/schemas/tender_extraction.json`
