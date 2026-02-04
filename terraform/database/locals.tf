# =============================================================================
# Environment-specific Configuration
# =============================================================================

locals {
  # Lower environments (non-prod) - add new environments here
  lower_environments = ["stage", "dev", "test"]
  is_lower_env       = contains(local.lower_environments, var.environment)

  # Environment-specific RDS settings
  db_config = {
    stage = {
      instance_class          = "db.t4g.micro"
      allocated_storage       = 20
      max_allocated_storage   = 50
      backup_retention_period = 7
      deletion_protection     = false
      skip_final_snapshot     = true
      multi_az                = false
      # Point-in-time recovery disabled for cost savings
      backup_window      = "03:00-04:00" # UTC
      maintenance_window = "Mon:04:00-Mon:05:00"
    }
    prod = {
      instance_class          = "db.t4g.medium"
      allocated_storage       = 50
      max_allocated_storage   = 200
      backup_retention_period = 14
      deletion_protection     = true
      skip_final_snapshot     = false
      multi_az                = false  # Single-AZ for cost savings (~$53/mo vs ~$104/mo)
      # Point-in-time recovery enabled (1-hour RPO)
      backup_window      = "03:00-04:00" # UTC
      maintenance_window = "Mon:04:00-Mon:05:00"
    }
  }

  # Current environment config (fallback to stage for undefined lower envs like dev/test)
  config = lookup(local.db_config, var.environment, local.db_config["stage"])

  # Resource naming
  name_prefix = "tosspaper-${var.environment}"
}
