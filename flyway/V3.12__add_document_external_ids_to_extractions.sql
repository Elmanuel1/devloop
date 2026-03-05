-- V3.12__add_document_external_ids_to_extractions.sql
-- Add document_external_ids JSONB column to extractions.
--
-- Each extraction processes one or more documents. When the ExtractionWorker
-- (TOS-38) submits a document to Reducto it records two identifiers:
--   externalTaskId  — the async job ID returned by Reducto (used to match
--                     incoming webhooks back to this extraction)
--   externalFileId  — the Reducto file-upload ID (allows the worker to skip
--                     re-uploading a file on retry)
--
-- The column stores a map keyed by document ID:
--   { "<documentId>": { "externalTaskId": "...", "externalFileId": "..." } }
--
-- An empty object '{}' is the default; rows that have not yet been submitted
-- to Reducto carry no entries.

ALTER TABLE extractions
    ADD COLUMN IF NOT EXISTS document_external_ids JSONB NOT NULL DEFAULT '{}';
