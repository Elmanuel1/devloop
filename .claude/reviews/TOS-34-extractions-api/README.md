# TOS-34 Extractions API — Review Folder

Review outputs from all agents. Leave inline comments or add a `COMMENTS.md` file.

## Files

| File | Agent | Status |
|------|-------|--------|
| `01-reviewer-report.md` | reviewer | Done — 10 violations found, 2 skipped by user |
| `02-test-proposals.md` | test-writer | REVISED 2026-02-25 — 216 test cases (up from 95) |
| `03-architect-plan.md` | architect | REVISED 2026-02-25 — 13 Confluence feedback items addressed |
| `04-open-questions.md` | — | All questions resolved (see 03-architect-plan.md top section) |
| `05-code-writer-status.md` | code-writer | In progress — fixing 8 violations |

## Critical Alert — Violation #8 Revert

`TenderDocumentRepository.findById` was changed from `Optional` to throwing internally
by the code-writer (violation #8). The user has since said this was intentional.
See the top of `03-architect-plan.md` for the revert instructions.

## How to leave feedback

Edit any file directly, or create `COMMENTS.md` with your notes. Reference files and section headers.
