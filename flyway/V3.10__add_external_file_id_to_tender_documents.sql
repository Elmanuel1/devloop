-- V3.10__add_external_file_id_to_tender_documents.sql
-- Add external_file_id to tender_documents.
--
-- Stores the Reducto file-upload ID per document so the ExtractionWorker
-- (TOS-38) can skip re-uploading a file that has already been submitted.
-- No unique index is required — the same Reducto file ID could in principle
-- be referenced by more than one document row.

ALTER TABLE tender_documents
    ADD COLUMN IF NOT EXISTS external_file_id VARCHAR(255);
