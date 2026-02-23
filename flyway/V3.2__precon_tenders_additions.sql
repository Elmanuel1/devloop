-- V1.47__precon_tenders_additions.sql
-- Alter tenders and tender_documents tables for precon-service.
-- Adds version column for optimistic locking.
-- Drops parent_document_id column (ZIP support removed).

-- ──────────────────── tenders: add version column ────────────────────

ALTER TABLE tenders
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- ──────────────────── tender_documents: drop parent_document_id ────────────────────

ALTER TABLE tender_documents
    DROP COLUMN IF EXISTS parent_document_id;
