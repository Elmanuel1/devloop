-- V3.9__move_external_task_id_to_tender_documents.sql
-- Move external_task_id from extractions to tender_documents.
--
-- Each Reducto job processes a single document, so external_task_id
-- is a per-document property. Moving it to tender_documents lets the
-- webhook handler look up the document directly by job_id.

-- ── Step 1: add external_task_id to tender_documents ──────────────────────

ALTER TABLE tender_documents
    ADD COLUMN IF NOT EXISTS external_task_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS tender_documents_external_task_id_uq
    ON tender_documents (external_task_id)
    WHERE deleted_at IS NULL
      AND external_task_id IS NOT NULL;

-- ── Step 2: remove external_task_id from extractions ──────────────────────

DROP INDEX IF EXISTS extractions_external_task_id_uq;

ALTER TABLE extractions
    DROP COLUMN IF EXISTS external_task_id;
