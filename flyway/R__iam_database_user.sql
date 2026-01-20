-- Repeatable migration: IAM Database User Setup
-- This migration is idempotent and can be re-run safely whenever permissions need updating.
-- Flyway will re-run this if the checksum changes.

-- Create IAM-enabled database role (if not exists)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'tosspaper') THEN
        CREATE ROLE tosspaper WITH LOGIN;
        RAISE NOTICE 'Created role tosspaper';
    END IF;
END
$$;

-- Grant rds_iam role for IAM authentication
-- This role exists on RDS instances with IAM auth enabled
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'rds_iam') THEN
        GRANT rds_iam TO tosspaper;
        RAISE NOTICE 'Granted rds_iam to tosspaper';
    ELSE
        RAISE NOTICE 'rds_iam role not found (not running on RDS or IAM auth not enabled)';
    END IF;
END
$$;

-- Grant access to the database
GRANT CONNECT ON DATABASE tosspaper TO tosspaper;

-- Grant schema access
GRANT USAGE ON SCHEMA public TO tosspaper;

-- Grant table permissions (existing tables)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO tosspaper;

-- Grant sequence permissions (for INSERT with auto-increment)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO tosspaper;

-- Set default privileges for future tables created by postgres (RDS master user)
-- This ensures new tables automatically get the same grants
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tosspaper;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO tosspaper;
