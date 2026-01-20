-- ============================================================
-- Auto-migrate existing company owners to authorized_users
-- ============================================================

-- Create authorized_users records for all existing companies
-- user_id is temporarily set to the email (will be updated to Supabase user_id on first login)

INSERT INTO authorized_users (
    id,
    company_id,
    user_id,
    email,
    role_id,
    role_name,
    status,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid()::text AS id,
    c.id AS company_id,
    c.email AS user_id, -- Temporary: will be replaced with actual Supabase user_id after first login
    c.email AS email,
    'owner' AS role_id,
    'Owner' AS role_name,
    'enabled' AS status,
    NOW() AS created_at,
    NOW() AS updated_at
FROM companies c
WHERE c.email IS NOT NULL
AND NOT EXISTS (
    -- Avoid duplicates if migration runs multiple times
    SELECT 1 FROM authorized_users au
    WHERE au.company_id = c.id AND au.email = c.email
);

-- Log migration results
DO $$
DECLARE
    migrated_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO migrated_count
    FROM authorized_users
    WHERE role_id = 'owner';

    RAISE NOTICE 'Migrated % existing company owners to authorized_users table', migrated_count;
END $$;
