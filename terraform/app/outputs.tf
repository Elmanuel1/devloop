# =============================================================================
# Outputs for Application Module
# =============================================================================

# EC2
output "ec2_instance_id" {
  description = "EC2 instance ID"
  value       = aws_instance.app.id
}

output "ec2_public_ip" {
  description = "Elastic IP address of the EC2 instance"
  value       = aws_eip.app.public_ip
}

output "ec2_private_ip" {
  description = "Private IP address of the EC2 instance"
  value       = aws_instance.app.private_ip
}

# S3
output "s3_bucket_name" {
  description = "Name of the S3 bucket for email attachments"
  value       = aws_s3_bucket.attachments.id
}

output "s3_bucket_arn" {
  description = "ARN of the S3 bucket"
  value       = aws_s3_bucket.attachments.arn
}

# SQS
output "sqs_queue_urls" {
  description = "URLs of all SQS queues"
  value = {
    email_local_uploads    = aws_sqs_queue.email_local_uploads.url
    ai_process             = aws_sqs_queue.ai_process.url
    vector_store_ingestion = aws_sqs_queue.vector_store_ingestion.url
    document_approved      = aws_sqs_queue.document_approved.url
    quickbooks_events      = aws_sqs_queue.quickbooks_events.url
    integration_push       = aws_sqs_queue.integration_push.url
  }
}

output "sqs_queue_arns" {
  description = "ARNs of all SQS queues"
  value = {
    email_local_uploads    = aws_sqs_queue.email_local_uploads.arn
    ai_process             = aws_sqs_queue.ai_process.arn
    vector_store_ingestion = aws_sqs_queue.vector_store_ingestion.arn
    document_approved      = aws_sqs_queue.document_approved.arn
    quickbooks_events      = aws_sqs_queue.quickbooks_events.arn
    integration_push       = aws_sqs_queue.integration_push.arn
  }
}

# IAM
output "app_role_arn" {
  description = "ARN of the IAM role for the application"
  value       = aws_iam_role.app.arn
}

output "instance_profile_name" {
  description = "Name of the EC2 instance profile"
  value       = aws_iam_instance_profile.app.name
}

# Security Groups
output "app_security_group_id" {
  description = "Security group ID for the application"
  value       = aws_security_group.app.id
}
