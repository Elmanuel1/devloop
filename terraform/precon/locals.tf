# =============================================================================
# Environment-specific Configuration
# =============================================================================

locals {
  # Lower environments (non-prod)
  lower_environments = ["stage", "dev", "test"]
  is_lower_env       = contains(local.lower_environments, var.environment)

  # Resource naming
  name_prefix = "tosspaper-${var.environment}"

  # Environment-specific settings
  precon_config = {
    stage = {
      s3_force_destroy = true # Allow easy cleanup
    }
    prod = {
      s3_force_destroy = false # Prevent accidental data loss
    }
  }

  # Current environment config (fallback to stage for undefined lower envs)
  config = lookup(local.precon_config, var.environment, local.precon_config["stage"])

  # SQS queue configuration
  tender_upload_queue = {
    visibility_timeout = 300 # 5 min for document processing
    max_receive_count  = 3
  }
}
