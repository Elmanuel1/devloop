-- Create company invitations table for RBAC system
-- Tracks email invitations to join companies with specific roles
-- Uses composite primary key (company_id, email) instead of invite_code

CREATE TABLE company_invitations (
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role_id VARCHAR(50) NOT NULL, -- owner, admin, operations, viewer (enum in code)
    role_name VARCHAR(50) NOT NULL, -- Owner, Admin, Operations, Viewer (display name)
    status VARCHAR(50) NOT NULL DEFAULT 'invited', -- invited, accepted, declined
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,

    -- Composite primary key: one invitation per company-email pair
    PRIMARY KEY (company_id, email)
);

-- Indexes for queries
CREATE INDEX idx_company_invitations_email ON company_invitations(email);
CREATE INDEX idx_company_invitations_status ON company_invitations(status);
CREATE INDEX idx_company_invitations_expires_at ON company_invitations(expires_at);

-- Comments
COMMENT ON TABLE company_invitations IS 'Email invitations to join companies. Uses composite PK (company_id, email). Re-inviting updates existing record.';
COMMENT ON COLUMN company_invitations.role_id IS 'Role identifier (owner, admin, operations, viewer) - no FK, roles defined in code';
COMMENT ON COLUMN company_invitations.role_name IS 'Display name for role (Owner, Admin, Operations, Viewer)';
COMMENT ON COLUMN company_invitations.status IS 'Invitation status: invited (pending), accepted, declined';
COMMENT ON COLUMN company_invitations.expires_at IS 'Expiration timestamp (24 hours from creation, matching Supabase token expiry)';
