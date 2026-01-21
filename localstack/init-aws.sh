#!/bin/bash
# LocalStack init hook - creates SQS queues and S3 bucket on startup
# This script runs automatically when LocalStack is ready
# Idempotent: safe to run on container restarts

set -e

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
  DLQ_URL=$(awslocal sqs get-queue-url --queue-name "${DLQ_NAME}" 2>/dev/null | jq -r '.QueueUrl' || echo "")
  if [ -z "$DLQ_URL" ]; then
    echo "Creating DLQ: ${DLQ_NAME}"
    DLQ_URL=$(awslocal sqs create-queue --queue-name "${DLQ_NAME}" | jq -r '.QueueUrl')
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
  QUEUE_URL=$(awslocal sqs get-queue-url --queue-name "${QUEUE_NAME}" 2>/dev/null | jq -r '.QueueUrl' || echo "")
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

echo "============================================"
echo "LocalStack initialization complete!"
echo "============================================"
