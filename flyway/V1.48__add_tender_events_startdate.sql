-- V1.48__add_tender_events_startdate.sql
-- Add events (JSONB) and start_date columns to tenders table.
-- Note: version column already added in V1.47.

ALTER TABLE tenders ADD COLUMN IF NOT EXISTS events JSONB;
ALTER TABLE tenders ADD COLUMN IF NOT EXISTS start_date DATE;
