-- Add refresh token expiry tracking to integration_connections
ALTER TABLE integration_connections
    ADD COLUMN IF NOT EXISTS refresh_token_expires_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN integration_connections.refresh_token_expires_at IS 'Expiry time for the refresh token. QuickBooks refresh tokens expire after 100 days.';
