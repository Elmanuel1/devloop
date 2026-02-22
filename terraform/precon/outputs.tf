# =============================================================================
# Outputs for Precon Module
# =============================================================================

# S3
output "s3_bucket_name" {
  description = "S3 bucket name for tender document uploads"
  value       = aws_s3_bucket.tender_uploads.id
}

output "s3_bucket_arn" {
  description = "S3 bucket ARN"
  value       = aws_s3_bucket.tender_uploads.arn
}

# SQS
output "sqs_queue_url" {
  description = "SQS queue URL for tender upload events"
  value       = aws_sqs_queue.tender_upload_events.url
}

output "sqs_queue_arn" {
  description = "SQS queue ARN for tender upload events"
  value       = aws_sqs_queue.tender_upload_events.arn
}

output "sqs_dlq_url" {
  description = "Dead letter queue URL for failed tender upload events"
  value       = aws_sqs_queue.tender_upload_events_dlq.url
}

output "sqs_dlq_arn" {
  description = "Dead letter queue ARN"
  value       = aws_sqs_queue.tender_upload_events_dlq.arn
}
