# =============================================================================
# AWS CloudTrail - Organization-wide Audit Trail
# =============================================================================
# Captures all API activity across all accounts in the organization
# Logs stored in central S3 bucket in security account
# =============================================================================

# -----------------------------------------------------------------------------
# CloudTrail (Organization Trail)
# -----------------------------------------------------------------------------
resource "aws_cloudtrail" "organization" {
  name                          = "tosspaper-organization-trail"
  s3_bucket_name                = aws_s3_bucket.log_archive.id
  s3_key_prefix                 = "cloudtrail"
  include_global_service_events = true
  is_multi_region_trail         = true
  is_organization_trail         = true
  enable_logging                = true

  # Enable log file validation for integrity
  enable_log_file_validation = true

  # Event selectors - capture management events
  event_selector {
    read_write_type           = "All"
    include_management_events = true
  }

  tags = {
    Name    = "tosspaper-organization-trail"
    Purpose = "SOC2 audit logging"
  }

  depends_on = [aws_s3_bucket_policy.log_archive]
}
