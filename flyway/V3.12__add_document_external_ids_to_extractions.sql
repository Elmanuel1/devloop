-- V3.12__add_document_external_ids_to_extractions.sql
-- Replace the per-extraction external_task_id column (V3.8) with per-document
-- columns on tender_documents, which is the natural owner of that data.
--
-- external_task_id  — the Reducto async job ID; used to match inbound webhooks
--                     back to the document that triggered them
-- external_file_id  — the Reducto file-upload ID; allows the ExtractionWorker
--                     to skip re-uploading the same file on retry
--
-- The old extractions.external_task_id (and its unique index) are dropped —
-- they are replaced by the per-document columns below.

DROP INDEX IF EXISTS extractions_external_task_id_uq;

ALTER TABLE extractions
    DROP COLUMN IF EXISTS external_task_id;

ALTER TABLE tender_documents
    ADD COLUMN IF NOT EXISTS external_task_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_file_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS tender_documents_external_task_id_uq
    ON tender_documents (external_task_id)
    WHERE deleted_at IS NULL
      AND external_task_id IS NOT NULL;
