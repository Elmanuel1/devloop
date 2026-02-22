# =============================================================================
# SQS Queues - Tender Upload Event Processing
# =============================================================================
# Receives S3 ObjectCreated notifications for tender document uploads.
# DLQ captures messages that fail processing after 3 attempts.
# =============================================================================

# -----------------------------------------------------------------------------
# Dead Letter Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "tender_upload_events_dlq" {
  name = "${local.name_prefix}-tender-upload-events-dlq"

  # DLQ retention - keep messages longer for debugging
  message_retention_seconds = 1209600 # 14 days

  # Encryption at rest
  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-tender-upload-events-dlq"
    Purpose = "Dead letter queue for tender upload event processing"
  }
}

# -----------------------------------------------------------------------------
# Tender Upload Events Queue
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "tender_upload_events" {
  name = "${local.name_prefix}-tender-upload-events"

  visibility_timeout_seconds = local.tender_upload_queue.visibility_timeout
  message_retention_seconds  = 86400 # 1 day
  receive_wait_time_seconds  = 20    # Long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.tender_upload_events_dlq.arn
    maxReceiveCount     = local.tender_upload_queue.max_receive_count
  })

  # Encryption at rest
  sqs_managed_sse_enabled = true

  tags = {
    Name    = "${local.name_prefix}-tender-upload-events"
    Purpose = "Process S3 upload notifications for tender documents"
  }
}

# -----------------------------------------------------------------------------
# SQS Queue Policy - Allow S3 to Send Notifications
# -----------------------------------------------------------------------------
resource "aws_sqs_queue_policy" "allow_s3_notifications" {
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
