# =============================================================================
# IAM Role and Instance Profile
# =============================================================================
# Provides EC2 instance with access to:
# - S3 bucket (email attachments)
# - SQS queues
# - Secrets Manager (DB credentials)
# - CloudWatch (logs and metrics)
#
# No AWS credentials stored on EC2 - uses instance profile
# =============================================================================

# -----------------------------------------------------------------------------
# IAM Role
# -----------------------------------------------------------------------------
resource "aws_iam_role" "app" {
  name        = "${local.name_prefix}-app-role"
  description = "IAM role for TossPaper application server"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = {
    Name = "${local.name_prefix}-app-role"
  }
}

# -----------------------------------------------------------------------------
# Instance Profile
# -----------------------------------------------------------------------------
resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-app-profile"
  role = aws_iam_role.app.name

  tags = {
    Name = "${local.name_prefix}-app-profile"
  }
}

# -----------------------------------------------------------------------------
# SSM Managed Policy (for Session Manager remote access)
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# -----------------------------------------------------------------------------
# S3 Policy
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "s3_access" {
  name = "s3-access"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3BucketAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket",
          "s3:GetBucketLocation"
        ]
        Resource = [
          aws_s3_bucket.attachments.arn,
          "${aws_s3_bucket.attachments.arn}/*"
        ]
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# SQS Policy
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "sqs_access" {
  name = "sqs-access"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SQSAccess"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = [
          aws_sqs_queue.email_local_uploads.arn,
          aws_sqs_queue.ai_process.arn,
          aws_sqs_queue.vector_store_ingestion.arn,
          aws_sqs_queue.document_approved.arn,
          aws_sqs_queue.quickbooks_events.arn,
          aws_sqs_queue.integration_push.arn,
          # DLQs
          aws_sqs_queue.email_local_uploads_dlq.arn,
          aws_sqs_queue.ai_process_dlq.arn,
          aws_sqs_queue.vector_store_ingestion_dlq.arn,
          aws_sqs_queue.document_approved_dlq.arn,
          aws_sqs_queue.quickbooks_events_dlq.arn,
          aws_sqs_queue.integration_push_dlq.arn,
        ]
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# CloudWatch Metrics Policy (Application + CloudWatch Agent)
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "cloudwatch_metrics" {
  name = "cloudwatch-metrics"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "CloudWatchMetrics"
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricData"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = ["Tosspaper", "CWAgent"]
          }
        }
      },
      {
        Sid    = "CloudWatchAgentDescribe"
        Effect = "Allow"
        Action = [
          "ec2:DescribeTags",
          "ec2:DescribeInstances",
          "autoscaling:DescribeAutoScalingInstances"
        ]
        Resource = "*"
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# RDS IAM Connect Policy (direct attachment to app role)
# -----------------------------------------------------------------------------
resource "aws_iam_role_policy" "rds_connect" {
  name = "rds-connect"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RdsIamConnect"
        Effect   = "Allow"
        Action   = "rds-db:connect"
        Resource = "arn:aws:rds-db:${data.aws_region.current.id}:${data.aws_caller_identity.current.account_id}:dbuser:${var.db_resource_id}/${var.db_iam_username}"
      }
    ]
  })
}
