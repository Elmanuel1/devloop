# =============================================================================
# IAM Policy - Tender Upload Access
# =============================================================================
# Grants the application role access to:
# - S3: PutObject, GetObject, DeleteObject for tender uploads
# - SQS: ReceiveMessage, DeleteMessage, GetQueueAttributes for upload events
#
# Attaches to the existing app role from the app module.
# =============================================================================

# -----------------------------------------------------------------------------
# S3 Access Policy
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "tender_s3_access" {
  name = "precon-tender-s3-access"
  role = var.app_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TenderUploadsBucketAccess"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject"
        ]
        Resource = "${aws_s3_bucket.tender_uploads.arn}/*"
      },
      {
        Sid    = "TenderUploadsBucketList"
        Effect = "Allow"
        Action = [
          "s3:ListBucket",
          "s3:GetBucketLocation"
        ]
        Resource = aws_s3_bucket.tender_uploads.arn
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# SQS Access Policy
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "tender_sqs_access" {
  name = "precon-tender-sqs-access"
  role = var.app_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TenderUploadQueueAccess"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = [
          aws_sqs_queue.tender_upload_events.arn,
          aws_sqs_queue.tender_upload_events_dlq.arn
        ]
      }
    ]
  })
}
