-- V3.5__add_timing_and_errors_to_extractions.sql
-- Add AI pipeline lifecycle columns to the extractions table:
--   started_at   — when the extraction processing began
--   completed_at — when the extraction processing finished
--   errors       — array of structured extraction errors (populated by AI pipeline)
-- All three are nullable; they are written by the AI pipeline after the row is created.

ALTER TABLE extractions ADD COLUMN IF NOT EXISTS started_at   TIMESTAMPTZ;
ALTER TABLE extractions ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE extractions ADD COLUMN IF NOT EXISTS errors       JSONB;
