-- Create authorized_users table for RBAC system
-- Replaces company_members - stores users authorized to access companies
-- References Supabase auth.users.id directly (no local users table)

CREATE TABLE authorized_users (
    id VARCHAR(50) PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    user_id VARCHAR(50) NOT NULL, -- Supabase auth.users.id
    email VARCHAR(255) NOT NULL,  -- Denormalized from JWT for queries
    role_id VARCHAR(50) NOT NULL, -- owner, admin, operations, viewer (enum in code)
    role_name VARCHAR(50) NOT NULL, -- Owner, Admin, Operations, Viewer (display name)
    status VARCHAR(50) NOT NULL DEFAULT 'enabled', -- enabled, disabled
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    last_updated_by VARCHAR(50),

    -- Ensure one user per company
    UNIQUE(company_id, user_id),

    -- Ensure one email per company (primary lookup key)
    UNIQUE(company_id, email)
);

-- Indexes for efficient lookups
CREATE INDEX idx_authorized_users_company_id ON authorized_users(company_id);
CREATE INDEX idx_authorized_users_user_id ON authorized_users(user_id);
CREATE INDEX idx_authorized_users_email ON authorized_users(email);
CREATE INDEX idx_authorized_users_status ON authorized_users(status);

-- Comments explaining the design
COMMENT ON TABLE authorized_users IS 'Users authorized to access companies with specific roles. user_id references Supabase auth.users.id directly.';
COMMENT ON COLUMN authorized_users.user_id IS 'Supabase auth.users.id - no local users table needed';
COMMENT ON COLUMN authorized_users.email IS 'Denormalized from Supabase JWT for query performance (primary lookup key)';
COMMENT ON COLUMN authorized_users.role_id IS 'Role identifier (owner, admin, operations, viewer) - no FK, roles defined in code';
COMMENT ON COLUMN authorized_users.role_name IS 'Display name for role (Owner, Admin, Operations, Viewer)';
