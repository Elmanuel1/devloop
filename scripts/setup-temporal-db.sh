#!/usr/bin/env bash
# =============================================================================
# Setup Temporal Databases on RDS
# =============================================================================
# Creates temporal and temporal_visibility databases with proper permissions.
# Run this ONCE when setting up a new environment.
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
#   - psql client (or Docker with postgres image)
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
RDS_HOST="tosspaper-${ENV}-postgres.cdak8uywcr2c.us-west-2.rds.amazonaws.com"
SECRET_ID="tosspaper-${ENV}/database/credentials"

echo "=== Temporal Database Setup for ${ENV} ==="
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

# Function to run SQL
run_sql() {
    local database="${1:-postgres}"
    local sql="$2"

    PGPASSWORD="$MASTER_PASS" psql \
        "sslmode=require host=${RDS_HOST} user=${MASTER_USER} dbname=${database}" \
        -c "$sql"
}

# Alternative: Use Docker if psql not installed
run_sql_docker() {
    local database="${1:-postgres}"
    local sql="$2"

    docker run --rm \
        -e PGPASSWORD="$MASTER_PASS" \
        postgres:15 \
        psql "sslmode=require host=${RDS_HOST} user=${MASTER_USER} dbname=${database}" \
        -c "$sql"
}

# Check if psql is available, otherwise use Docker
if command -v psql &> /dev/null; then
    SQL_CMD="run_sql"
    echo "Using local psql client"
else
    SQL_CMD="run_sql_docker"
    echo "Using Docker postgres:15 image"
fi

echo ""
echo "--- Step 1: Create temporal user ---"
$SQL_CMD postgres "CREATE USER temporal WITH PASSWORD '${TEMPORAL_PASSWORD}';" 2>/dev/null || echo "User 'temporal' may already exist, continuing..."

echo ""
echo "--- Step 2: Create temporal database ---"
$SQL_CMD postgres "CREATE DATABASE temporal;" 2>/dev/null || echo "Database 'temporal' may already exist, continuing..."

echo ""
echo "--- Step 3: Create temporal_visibility database ---"
$SQL_CMD postgres "CREATE DATABASE temporal_visibility;" 2>/dev/null || echo "Database 'temporal_visibility' may already exist, continuing..."

echo ""
echo "--- Step 4: Grant database privileges ---"
$SQL_CMD postgres "GRANT ALL PRIVILEGES ON DATABASE temporal TO temporal;"
$SQL_CMD postgres "GRANT ALL PRIVILEGES ON DATABASE temporal_visibility TO temporal;"

echo ""
echo "--- Step 5: Set database ownership ---"
$SQL_CMD postgres "ALTER DATABASE temporal OWNER TO temporal;"
# Note: temporal_visibility ownership is optional

echo ""
echo "--- Step 6: Grant schema privileges ---"
$SQL_CMD temporal "GRANT ALL ON SCHEMA public TO temporal;"
$SQL_CMD temporal_visibility "GRANT ALL ON SCHEMA public TO temporal;"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Databases created:"
echo "  - temporal"
echo "  - temporal_visibility"
echo ""
echo "User: temporal"
echo "Password: (as provided)"
echo ""
echo "Next steps:"
echo "  1. Add TEMPORAL_DB_PASSWORD to SSM Parameter Store:"
echo "     aws ssm put-parameter --name '/tosspaper/${ENV}/secrets/TEMPORAL_DB_PASSWORD' \\"
echo "         --value '${TEMPORAL_PASSWORD}' --type SecureString --region ${REGION}"
echo ""
echo "  2. Deploy the application - Temporal will auto-migrate the schema"
