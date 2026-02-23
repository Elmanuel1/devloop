# =============================================================================
# SQS Queues - Message Processing
# =============================================================================
# 6 queues + 6 DLQs matching application.yml configuration:
# - email-local-uploads
# - ai-process
# - vector-store-ingestion
# - document-approved-events
# - quickbooks-events
# - integration-push-events
# =============================================================================

# -----------------------------------------------------------------------------
# email-local-uploads Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "email_local_uploads_dlq" {
  name = "${local.name_prefix}-email-local-uploads-dlq"

  # DLQ retention - keep messages longer for debugging
  message_retention_seconds = 1209600 # 14 days

  # Encryption at rest
  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-email-local-uploads-dlq"
    Purpose = "Dead letter queue for email local uploads"
  }
}

resource "aws_sqs_queue" "email_local_uploads" {
  name = "${local.name_prefix}-email-local-uploads"

  visibility_timeout_seconds = local.sqs_queues.email_local_uploads.visibility_timeout
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20     # Long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.email_local_uploads_dlq.arn
    maxReceiveCount     = local.sqs_queues.email_local_uploads.max_receive_count
  })

  # Encryption
  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-email-local-uploads"
    Purpose = "Process email attachments and upload to S3"
  }
}

# -----------------------------------------------------------------------------
# ai-process Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "ai_process_dlq" {
  name                      = "${local.name_prefix}-ai-process-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Name = "${local.name_prefix}-ai-process-dlq"
  }
}

resource "aws_sqs_queue" "ai_process" {
  name = "${local.name_prefix}-ai-process"

  visibility_timeout_seconds = local.sqs_queues.ai_process.visibility_timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ai_process_dlq.arn
    maxReceiveCount     = local.sqs_queues.ai_process.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-ai-process"
    Purpose = "AI extraction and processing"
  }
}

# -----------------------------------------------------------------------------
# vector-store-ingestion Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "vector_store_ingestion_dlq" {
  name                      = "${local.name_prefix}-vector-store-ingestion-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Name = "${local.name_prefix}-vector-store-ingestion-dlq"
  }
}

resource "aws_sqs_queue" "vector_store_ingestion" {
  name = "${local.name_prefix}-vector-store-ingestion"

  visibility_timeout_seconds = local.sqs_queues.vector_store_ingestion.visibility_timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.vector_store_ingestion_dlq.arn
    maxReceiveCount     = local.sqs_queues.vector_store_ingestion.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-vector-store-ingestion"
    Purpose = "Vector embedding ingestion"
  }
}

# -----------------------------------------------------------------------------
# document-approved-events Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "document_approved_dlq" {
  name                      = "${local.name_prefix}-document-approved-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Name = "${local.name_prefix}-document-approved-events-dlq"
  }
}

resource "aws_sqs_queue" "document_approved" {
  name = "${local.name_prefix}-document-approved-events"

  visibility_timeout_seconds = local.sqs_queues.document_approved.visibility_timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.document_approved_dlq.arn
    maxReceiveCount     = local.sqs_queues.document_approved.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-document-approved-events"
    Purpose = "Document approval notifications"
  }
}

# -----------------------------------------------------------------------------
# quickbooks-events Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "quickbooks_events_dlq" {
  name                      = "${local.name_prefix}-quickbooks-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Name = "${local.name_prefix}-quickbooks-events-dlq"
  }
}

resource "aws_sqs_queue" "quickbooks_events" {
  name = "${local.name_prefix}-quickbooks-events"

  visibility_timeout_seconds = local.sqs_queues.quickbooks_events.visibility_timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.quickbooks_events_dlq.arn
    maxReceiveCount     = local.sqs_queues.quickbooks_events.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-quickbooks-events"
    Purpose = "QuickBooks webhook processing"
  }
}

# -----------------------------------------------------------------------------
# integration-push-events Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "integration_push_dlq" {
  name                      = "${local.name_prefix}-integration-push-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = {
    Name = "${local.name_prefix}-integration-push-events-dlq"
  }
}

resource "aws_sqs_queue" "integration_push" {
  name = "${local.name_prefix}-integration-push-events"

  visibility_timeout_seconds = local.sqs_queues.integration_push.visibility_timeout
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.integration_push_dlq.arn
    maxReceiveCount     = local.sqs_queues.integration_push.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-integration-push-events"
    Purpose = "Integration push sync"
  }
}

# -----------------------------------------------------------------------------
# tender-upload-events Queue (S3 ObjectCreated notifications)
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "tender_upload_events_dlq" {
  name                      = "${local.name_prefix}-tender-upload-events-dlq"
  message_retention_seconds = 1209600 # 14 days
  sqs_managed_sse_enabled   = true

  tags = {
    Name    = "${local.name_prefix}-tender-upload-events-dlq"
    Purpose = "Dead letter queue for tender upload event processing"
  }
}

resource "aws_sqs_queue" "tender_upload_events" {
  name = "${local.name_prefix}-tender-upload-events"

  visibility_timeout_seconds = local.sqs_queues.tender_upload_events.visibility_timeout
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20    # Long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.tender_upload_events_dlq.arn
    maxReceiveCount     = local.sqs_queues.tender_upload_events.max_receive_count
  })

  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-tender-upload-events"
    Purpose = "Process S3 upload notifications for tender documents"
  }
}

resource "aws_sqs_queue_policy" "allow_s3_tender_notifications" {
  queue_url = aws_sqs_queue.tender_upload_events.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowS3Notifications"
        Effect    = "Allow"
        Principal = { Service = "s3.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.tender_upload_events.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_s3_bucket.tender_uploads.arn
          }
        }
      }
    ]
  })
}
