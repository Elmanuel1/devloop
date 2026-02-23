# =============================================================================
# S3 Bucket - Email Attachments
# =============================================================================
# Stores email attachments and document uploads
# =============================================================================

# -----------------------------------------------------------------------------
# S3 Bucket
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "attachments" {
  bucket = "tosspaper-email-attachments-${var.environment}"

  # Force destroy only in stage (for easy cleanup)
  force_destroy = local.config.s3_force_destroy

  tags = {
    Name    = "tosspaper-email-attachments-${var.environment}"
    Purpose = "Email attachments and document storage"
  }
}

# -----------------------------------------------------------------------------
# Bucket Versioning
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "attachments" {
  bucket = aws_s3_bucket.attachments.id

  versioning_configuration {
    status = !local.is_lower_env ? "Enabled" : "Suspended"
  }
}

# -----------------------------------------------------------------------------
# Server-Side Encryption
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_server_side_encryption_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id

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
resource "aws_s3_bucket_public_access_block" "attachments" {
  bucket = aws_s3_bucket.attachments.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Bucket Policy (Require HTTPS)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_policy" "attachments" {
  bucket = aws_s3_bucket.attachments.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.attachments.arn,
          "${aws_s3_bucket.attachments.arn}/*"
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
# Lifecycle Rules (Cost Optimization)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id

  # Move old attachments to cheaper storage
  rule {
    id     = "archive-old-attachments"
    status = "Enabled"

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 365
      storage_class = "GLACIER"
    }

    # Delete after 7 years (adjust based on retention requirements)
    # Note: expiration days must be > last transition days (GLACIER at 365)
    expiration {
      days = !local.is_lower_env ? 2555 : 400 # 7 years prod, ~13 months stage
    }
  }

  # Clean up incomplete multipart uploads
  rule {
    id     = "cleanup-multipart"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# -----------------------------------------------------------------------------
# CORS Configuration (if frontend needs direct S3 access)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_cors_configuration" "attachments" {
  bucket = aws_s3_bucket.attachments.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "POST"]
    allowed_origins = var.s3_cors_allowed_origins
    expose_headers  = ["ETag", "x-amz-meta-custom-header"]
    max_age_seconds = 3000
  }
}

# =============================================================================
# S3 Bucket - Tender Document Uploads
# =============================================================================
# Stores tender documents uploaded via presigned PUT URLs.
# S3 event notifications trigger SQS for downstream processing.
# =============================================================================

resource "aws_s3_bucket" "tender_uploads" {
  bucket        = "${local.name_prefix}-tender-uploads"
  force_destroy = local.config.s3_force_destroy

  tags = {
    Name    = "${local.name_prefix}-tender-uploads"
    Purpose = "Tender document uploads via presigned URLs"
  }
}

resource "aws_s3_bucket_versioning" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  versioning_configuration {
    status = !local.is_lower_env ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

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

resource "aws_s3_bucket_lifecycle_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  rule {
    id     = "archive-old-uploads"
    status = "Enabled"

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    transition {
      days          = 365
      storage_class = "GLACIER"
    }
  }

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "tender_uploads" {
  bucket = aws_s3_bucket.tender_uploads.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT"]
    allowed_origins = var.s3_cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_bucket_notification" "tender_upload_events" {
  bucket = aws_s3_bucket.tender_uploads.id

  queue {
    queue_arn = aws_sqs_queue.tender_upload_events.arn
    events    = ["s3:ObjectCreated:*"]
  }

  depends_on = [aws_sqs_queue_policy.allow_s3_tender_notifications]
}
