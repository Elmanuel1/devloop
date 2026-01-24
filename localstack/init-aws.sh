#!/bin/bash
# LocalStack init hook - creates SQS queues and S3 bucket on startup
# This script runs automatically when LocalStack is ready
# Idempotent: safe to run on container restarts

set -e

# Use us-west-2 region for all AWS operations
export AWS_DEFAULT_REGION=us-west-2

# =============================================================================
# S3 BUCKET CREATION
# =============================================================================
BUCKET_NAME="tosspaper-email-attachments"

if ! awslocal s3api head-bucket --bucket "${BUCKET_NAME}" 2>/dev/null; then
  echo "Creating S3 bucket: ${BUCKET_NAME}"
  awslocal s3 mb "s3://${BUCKET_NAME}"
else
  echo "S3 bucket already exists: ${BUCKET_NAME}"
fi

echo "S3 buckets:"
awslocal s3 ls

# =============================================================================
# SQS QUEUES CREATION
# =============================================================================
QUEUE_PREFIX="tosspaper"
QUEUES=(
  "email-local-uploads"
  "ai-process"
  "vector-store-ingestion"
  "document-approved-events"
  "quickbooks-events"
  "integration-push-events"
)

echo "Creating SQS queues with DLQs..."

for queue in "${QUEUES[@]}"; do
  QUEUE_NAME="${QUEUE_PREFIX}-${queue}"
  DLQ_NAME="${QUEUE_NAME}-dlq"

  # Create DLQ first (if not exists)
  DLQ_URL=$(awslocal sqs get-queue-url --queue-name "${DLQ_NAME}" --query 'QueueUrl' --output text 2>/dev/null || echo "")
  if [ -z "$DLQ_URL" ]; then
    echo "Creating DLQ: ${DLQ_NAME}"
    DLQ_URL=$(awslocal sqs create-queue --queue-name "${DLQ_NAME}" --query 'QueueUrl' --output text)
  else
    echo "DLQ already exists: ${DLQ_NAME}"
  fi

  # Get DLQ ARN
  DLQ_ARN=$(awslocal sqs get-queue-attributes \
    --queue-url "$DLQ_URL" \
    --attribute-names QueueArn \
    --query 'Attributes.QueueArn' \
    --output text)

  # Create main queue with redrive policy (3 retries before DLQ)
  QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "${QUEUE_NAME}" --query 'QueueUrl' --output text 2>/dev/null || echo "")
  if [ -z "$QUEUE_URL" ]; then
    echo "Creating queue: ${QUEUE_NAME} -> DLQ: ${DLQ_NAME}"
    awslocal sqs create-queue \
      --queue-name "${QUEUE_NAME}" \
      --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
  else
    echo "Queue already exists: ${QUEUE_NAME}"
  fi
done

echo "SQS queues created successfully!"
awslocal sqs list-queues

# =============================================================================
# SSM PARAMETER STORE SETUP
# =============================================================================
SECRETS_FILE="/etc/localstack/secrets/local-secrets.json"
CONFIG_FILE="/etc/localstack/secrets/local-config.json"
PARAM_PREFIX="/tosspaper/local"

echo "Setting up SSM Parameter Store..."

# Create SecureString parameters from local-secrets.json
if [ ! -f "${SECRETS_FILE}" ]; then
  echo "WARNING: Secrets file not found: ${SECRETS_FILE}"
  echo "Skipping secrets setup. Create secrets/local-secrets.json to enable."
else
  echo "Creating SecureString parameters from local-secrets.json..."
  # Use Python with boto3 to avoid AWS CLI URL parsing issues
  python3 << EOF
import json
import boto3

ssm = boto3.client('ssm', endpoint_url='http://localhost:4566', region_name='us-west-2',
                   aws_access_key_id='test', aws_secret_access_key='test')

with open('${SECRETS_FILE}') as f:
    data = json.load(f)

for key, value in data.items():
    if value is None or value == '':  # Skip None and empty string only
        print(f'  Skipping empty: {key}')
        continue
    param_name = f'${PARAM_PREFIX}/secrets/{key}'
    print(f'  Creating parameter: {param_name}')
    try:
        ssm.put_parameter(Name=param_name, Value=str(value), Type='SecureString', Overwrite=True)
    except Exception as e:
        print(f'  ERROR: {key} - {e}')
EOF
  echo "SecureString parameters created successfully!"
fi

# Create String parameters from local-config.json
if [ ! -f "${CONFIG_FILE}" ]; then
  echo "WARNING: Config file not found: ${CONFIG_FILE}"
  echo "Skipping config setup. Create secrets/local-config.json to enable."
else
  echo "Creating String parameters from local-config.json..."
  # Use Python with boto3 to avoid AWS CLI URL parsing issues
  python3 << EOF
import json
import boto3

ssm = boto3.client('ssm', endpoint_url='http://localhost:4566', region_name='us-west-2',
                   aws_access_key_id='test', aws_secret_access_key='test')

with open('${CONFIG_FILE}') as f:
    data = json.load(f)

for key, value in data.items():
    if value is None or value == '':  # Skip None and empty string only
        print(f'  Skipping empty: {key}')
        continue
    param_name = f'${PARAM_PREFIX}/config/{key}'
    print(f'  Creating parameter: {param_name}')
    try:
        ssm.put_parameter(Name=param_name, Value=str(value), Type='String', Overwrite=True)
    except Exception as e:
        print(f'  ERROR: {key} - {e}')
EOF
  echo "String parameters created successfully!"
fi

echo "SSM Parameter Store parameters:"
awslocal ssm get-parameters-by-path --path "${PARAM_PREFIX}" --recursive --query 'Parameters[].Name' --output table

echo "============================================"
echo "LocalStack initialization complete!"
echo "============================================"
