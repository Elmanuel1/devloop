# =============================================================================
# Variables for Application Module
# =============================================================================

variable "environment" {
  description = "Environment name (stage or prod)"
  type        = string

  validation {
    condition     = contains(["stage", "prod"], var.environment)
    error_message = "Environment must be 'stage' or 'prod'."
  }
}

variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "account_id" {
  description = "AWS Account ID for the target environment"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID from the network account"
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs from the network account (EC2 with public IPs)"
  type        = list(string)
}

variable "db_security_group_id" {
  description = "Security group ID of the database (for app SG rules)"
  type        = string
}

variable "aws_profile" {
  description = "AWS profile to use for authentication"
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# RDS IAM Authentication Variables
# -----------------------------------------------------------------------------
variable "db_resource_id" {
  description = "RDS DBI resource ID (from database module output, format: dbi-resource_id)"
  type        = string
}

variable "db_iam_username" {
  description = "Database username for IAM authentication (must have rds_iam role granted)"
  type        = string
  default     = "tosspaper"
}

# -----------------------------------------------------------------------------
# Auto Scaling Group Variables
# -----------------------------------------------------------------------------
variable "asg_min_size" {
  description = "Minimum number of instances in the Auto Scaling Group"
  type        = number
  default     = 1
}

variable "asg_max_size" {
  description = "Maximum number of instances in the Auto Scaling Group"
  type        = number
  default     = 2
}

# -----------------------------------------------------------------------------
# Alerting Variables
# -----------------------------------------------------------------------------
variable "alert_email" {
  description = "Email address to receive CloudWatch alarm notifications"
  type        = string
  default     = ""
}
