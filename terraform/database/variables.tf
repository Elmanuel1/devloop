# =============================================================================
# Variables for Database Module
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
  description = "VPC ID (shared from network account)"
  type        = string
}

variable "database_subnet_ids" {
  description = "Database subnet IDs (shared from network account)"
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs (for security group rules)"
  type        = list(string)
}

variable "db_name" {
  description = "Database name"
  type        = string
  default     = "tosspaper"
}

variable "db_username" {
  description = "Database master username"
  type        = string
  default     = "postgres"
}

variable "aws_profile" {
  description = "AWS profile to use for authentication"
  type        = string
  default     = null
}

variable "alert_email" {
  description = "Email address for CloudWatch alarm notifications (prod only)"
  type        = string
  default     = ""
}