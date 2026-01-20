-- ============================================================
-- Fix authorized_users.user_id column length
-- ============================================================
-- Issue: V1.3 migration inserts c.email into user_id as a temporary value,
-- but user_id was defined as VARCHAR(50) while email can be VARCHAR(255).
-- This causes "value too long" errors if any company email exceeds 50 chars.
--
-- Solution: Increase user_id column to VARCHAR(255) to accommodate emails.

ALTER TABLE authorized_users
    ALTER COLUMN user_id TYPE VARCHAR(255);

COMMENT ON COLUMN authorized_users.user_id IS 'Supabase auth.users.id - may temporarily contain email until first login';
