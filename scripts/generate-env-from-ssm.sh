#!/usr/bin/env bash
# Generate springboot_dev.env with Temporal config from SSM
# Usage: ./scripts/generate-env-from-ssm.sh <environment> [output-file]

set -e

ENV="${1:-stage}"
OUTPUT_FILE="${2:-./springboot_dev.env}"
REGION="${AWS_REGION:-us-west-2}"

echo "Generating env file for Temporal..."
echo "  Environment: $ENV"
echo "  Output: $OUTPUT_FILE"

# Fetch Temporal config from SSM
CONFIG_PATH="/tosspaper/${ENV}/config"
SECRETS_PATH="/tosspaper/${ENV}/secrets"

TEMPORAL_DB_HOST=$(aws ssm get-parameter --name "$CONFIG_PATH/TEMPORAL_DB_HOST" --region "$REGION" --query 'Parameter.Value' --output text)
TEMPORAL_DB_PORT=$(aws ssm get-parameter --name "$CONFIG_PATH/TEMPORAL_DB_PORT" --region "$REGION" --query 'Parameter.Value' --output text)
TEMPORAL_DB_USER=$(aws ssm get-parameter --name "$CONFIG_PATH/TEMPORAL_DB_USER" --region "$REGION" --query 'Parameter.Value' --output text)
TEMPORAL_DB_PASSWORD=$(aws ssm get-parameter --name "$SECRETS_PATH/TEMPORAL_DB_PASSWORD" --with-decryption --region "$REGION" --query 'Parameter.Value' --output text)

# Claude CLI API key (needed as env var for subprocess)
ANTHROPIC_API_KEY=$(aws ssm get-parameter --name "$SECRETS_PATH/ANTHROPIC_API_KEY" --with-decryption --region "$REGION" --query 'Parameter.Value' --output text 2>/dev/null || echo "")

# Write env file
cat > "$OUTPUT_FILE" << EOF
# Environment and region
ENV=${ENV}
AWS_REGION=${REGION}

# Temporal configuration (generated from SSM)
TEMPORAL_VERSION=1.24.2
TEMPORAL_DB_HOST=${TEMPORAL_DB_HOST}
TEMPORAL_DB_PORT=${TEMPORAL_DB_PORT}
TEMPORAL_DB_USER=${TEMPORAL_DB_USER}
TEMPORAL_DB_PASSWORD=${TEMPORAL_DB_PASSWORD}

# Claude CLI (needed as env var for subprocess)
ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
EOF

chmod 600 "$OUTPUT_FILE"
echo "Done. Generated $OUTPUT_FILE"
