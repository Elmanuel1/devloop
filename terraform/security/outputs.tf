# =============================================================================
# Outputs for Security Module
# =============================================================================

# Log Archive Bucket
output "log_archive_bucket_name" {
  description = "Name of the central log archive S3 bucket"
  value       = aws_s3_bucket.log_archive.id
}

output "log_archive_bucket_arn" {
  description = "ARN of the central log archive S3 bucket"
  value       = aws_s3_bucket.log_archive.arn
}

# CloudTrail
output "cloudtrail_arn" {
  description = "ARN of the organization CloudTrail"
  value       = aws_cloudtrail.organization.arn
}

# GuardDuty
output "guardduty_detector_id" {
  description = "GuardDuty detector ID"
  value       = aws_guardduty_detector.main.id
}

# Security Hub
output "securityhub_arn" {
  description = "Security Hub ARN"
  value       = aws_securityhub_account.main.id
}
