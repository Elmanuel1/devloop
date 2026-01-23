# =============================================================================
# Outputs for Application Module
# =============================================================================

# ALB
output "alb_dns_name" {
  description = "DNS name of the Application Load Balancer"
  value       = aws_lb.app.dns_name
}

output "alb_zone_id" {
  description = "Zone ID of the Application Load Balancer (for Route53/Cloudflare alias)"
  value       = aws_lb.app.zone_id
}

output "alb_arn" {
  description = "ARN of the Application Load Balancer"
  value       = aws_lb.app.arn
}

# ASG
output "asg_name" {
  description = "Name of the Auto Scaling Group"
  value       = aws_autoscaling_group.app.name
}

output "asg_arn" {
  description = "ARN of the Auto Scaling Group"
  value       = aws_autoscaling_group.app.arn
}

output "launch_template_id" {
  description = "ID of the Launch Template"
  value       = aws_launch_template.app.id
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

output "alb_security_group_id" {
  description = "Security group ID for the ALB"
  value       = aws_security_group.alb.id
}

# EFS
output "efs_id" {
  description = "EFS filesystem ID for persistent file storage"
  value       = aws_efs_file_system.app_files.id
}

output "efs_dns_name" {
  description = "EFS DNS name"
  value       = aws_efs_file_system.app_files.dns_name
}
