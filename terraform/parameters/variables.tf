# =============================================================================
# Variables for Parameters Module
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

variable "aws_profile" {
  description = "AWS profile to use for authentication"
  type        = string
  default     = null
}
