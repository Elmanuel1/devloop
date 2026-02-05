#!/usr/bin/env bash
# =============================================================================
# Setup Temporal User on RDS (Idempotent)
# =============================================================================
# Creates temporal user with CREATEDB privilege. Temporal auto-setup will
# create the databases (temporal, temporal_visibility) on first run.
#
# This script is idempotent - safe to run multiple times.
#
# Usage:
#   ./scripts/setup-temporal-db.sh <environment> <temporal_password>
#
# Example:
#   ./scripts/setup-temporal-db.sh stage mySecurePassword123
#   ./scripts/setup-temporal-db.sh prod mySecurePassword456
#
# Prerequisites:
#   - AWS CLI configured with appropriate permissions
#   - Access to Secrets Manager for RDS master credentials
#   - Docker (uses postgres:15 image)
# =============================================================================

set -e

ENV="${1:-}"
TEMPORAL_PASSWORD="${2:-}"

if [[ -z "$ENV" || -z "$TEMPORAL_PASSWORD" ]]; then
    echo "Usage: $0 <environment> <temporal_password>"
    echo "Example: $0 stage mySecurePassword123"
    exit 1
fi

# Configuration
REGION="${AWS_REGION:-us-west-2}"
if [[ "$ENV" == "prod" ]]; then
    RDS_HOST="tosspaper-prod-postgres.cl8agukk4g2s.us-west-2.rds.amazonaws.com"
else
    RDS_HOST="tosspaper-stage-postgres.cdak8uywcr2c.us-west-2.rds.amazonaws.com"
fi
SECRET_ID="tosspaper-${ENV}/database/credentials"

echo "=== Temporal User Setup for ${ENV} ==="
echo "RDS Host: ${RDS_HOST}"
echo ""

# Get master credentials from Secrets Manager
echo "Fetching master credentials from Secrets Manager..."
MASTER_SECRET=$(aws secretsmanager get-secret-value \
    --secret-id "$SECRET_ID" \
    --query SecretString \
    --output text \
    --region "$REGION")

MASTER_USER=$(echo "$MASTER_SECRET" | jq -r '.username')
MASTER_PASS=$(echo "$MASTER_SECRET" | jq -r '.password')

if [[ -z "$MASTER_USER" || -z "$MASTER_PASS" ]]; then
    echo "ERROR: Could not retrieve master credentials"
    exit 1
fi

echo "Master user: ${MASTER_USER}"
echo ""

# Function to run SQL with Docker
run_sql() {
    local database="${1:-postgres}"
    local sql="$2"

    docker run --rm \
        -e PGPASSWORD="$MASTER_PASS" \
        postgres:15 \
        psql "sslmode=require host=${RDS_HOST} user=${MASTER_USER} dbname=${database}" \
        -c "$sql"
}

echo "--- Creating temporal user with CREATEDB privilege (idempotent) ---"
run_sql postgres "
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'temporal') THEN
        CREATE USER temporal WITH PASSWORD '${TEMPORAL_PASSWORD}' CREATEDB;
        RAISE NOTICE 'User temporal created';
    ELSE
        ALTER USER temporal WITH PASSWORD '${TEMPORAL_PASSWORD}' CREATEDB;
        RAISE NOTICE 'User temporal updated';
    END IF;
END
\$\$;
"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "User: temporal"
echo "Privileges: CREATEDB (can create temporal, temporal_visibility databases)"
echo ""
echo "Temporal auto-setup will create these databases on first deploy:"
echo "  - temporal"
echo "  - temporal_visibility"
echo ""
echo "Next steps:"
echo "  1. Add TEMPORAL_DB_PASSWORD to SSM Parameter Store:"
echo "     aws ssm put-parameter --name '/tosspaper/${ENV}/secrets/TEMPORAL_DB_PASSWORD' \\"
echo "         --value '${TEMPORAL_PASSWORD}' --type SecureString --region ${REGION}"
echo ""
echo "  2. Deploy the application - Temporal will auto-create and migrate databases"
