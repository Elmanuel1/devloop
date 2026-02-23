# =============================================================================
# Environment-specific Configuration
# =============================================================================

locals {
  # Lower environments (non-prod) - add new environments here
  lower_environments = ["stage", "dev", "test"]
  is_lower_env       = contains(local.lower_environments, var.environment)

  # Environment-specific settings
  app_config = {
    stage = {
      instance_type        = "t4g.medium" # 2 vCPU, 4GB RAM
      use_spot             = true         # ~70% cost savings
      ebs_volume_size      = 30
      s3_force_destroy     = true  # Allow easy cleanup
      use_elb_health_check = false # EC2 only - don't terminate on ALB failures
    }
    prod = {
      instance_type        = "t4g.medium" # 2 vCPU, 4GB RAM
      use_spot             = false        # On-Demand for reliability
      ebs_volume_size      = 50
      s3_force_destroy     = false # Prevent accidental data loss
      use_elb_health_check = false # EC2 only - alert on app errors, don't terminate
    }
  }

  # Current environment config (fallback to stage for undefined lower envs like dev/test)
  config = lookup(local.app_config, var.environment, local.app_config["stage"])

  # Resource naming
  name_prefix = "tosspaper-${var.environment}"

  # SQS queue configurations (matching application.yml)
  sqs_queues = {
    email_local_uploads = {
      visibility_timeout = 300 # 5 min for S3 upload
      max_receive_count  = 3
    }
    ai_process = {
      visibility_timeout = 600 # 10 min for AI extraction
      max_receive_count  = 3
    }
    vector_store_ingestion = {
      visibility_timeout = 60 # 1 min for vector embedding
      max_receive_count  = 3
    }
    document_approved = {
      visibility_timeout = 120 # 2 min for notification
      max_receive_count  = 3
    }
    quickbooks_events = {
      visibility_timeout = 300 # 5 min for webhook sync
      max_receive_count  = 3
    }
    integration_push = {
      visibility_timeout = 600 # 10 min for push sync
      max_receive_count  = 3
    }
    tender_upload_events = {
      visibility_timeout = 300 # 5 min for document processing
      max_receive_count  = 3
    }
  }
}
