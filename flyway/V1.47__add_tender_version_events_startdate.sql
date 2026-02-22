-- V1.47__add_tender_version_events_startdate.sql
-- Add version (optimistic concurrency), events (JSONB), and start_date columns to tenders table.

ALTER TABLE tenders ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenders ADD COLUMN events JSONB;
ALTER TABLE tenders ADD COLUMN start_date DATE;
