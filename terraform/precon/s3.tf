# =============================================================================
# S3 Bucket - Tender Document Uploads
# =============================================================================
# Stores tender documents uploaded via presigned PUT URLs.
# S3 event notifications trigger SQS for downstream processing.
# =============================================================================

# -----------------------------------------------------------------------------
# S3 Bucket
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "tender_uploads" {
  bucket = "${local.name_prefix}-tender-uploads"

  # Force destroy only in stage (for easy cleanup)
  force_destroy = local.config.s3_force_destroy

  tags = {
    Name    = "${local.name_prefix}-tender-uploads"
    Purpose = "Tender document uploads via presigned URLs"
  }
}

# -----------------------------------------------------------------------------
# Bucket Versioning
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  versioning_configuration {
    status = !local.is_lower_env ? "Enabled" : "Suspended"
  }
}

# -----------------------------------------------------------------------------
# Server-Side Encryption
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_server_side_encryption_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# -----------------------------------------------------------------------------
# Block Public Access
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_public_access_block" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Bucket Policy (Require HTTPS)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_policy" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.tender_uploads.arn,
          "${aws_s3_bucket.tender_uploads.arn}/*"
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# Lifecycle Rules
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  # Archive old tender documents to cheaper storage
  rule {
    id     = "archive-old-uploads"
    status = "Enabled"

    filter {
      prefix = "tender-uploads/"
    }

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 365
      storage_class = "GLACIER"
    }

    expiration {
      days = !local.is_lower_env ? 2555 : 400 # 7 years prod, ~13 months stage
    }
  }

  # Clean up incomplete multipart uploads
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# -----------------------------------------------------------------------------
# CORS Configuration (presigned PUT uploads from frontend)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_cors_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT"]
    allowed_origins = var.s3_cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3600
  }
}

# -----------------------------------------------------------------------------
# S3 Event Notification -> SQS
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_notification" "tender_upload_events" {
  bucket = aws_s3_bucket.tender_uploads.id

  queue {
    queue_arn     = aws_sqs_queue.tender_upload_events.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "tender-uploads/"
  }

  depends_on = [aws_sqs_queue_policy.allow_s3_notifications]
}
