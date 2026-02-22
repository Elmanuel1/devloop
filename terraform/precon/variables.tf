# =============================================================================
# Variables for Precon Module
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

variable "app_role_name" {
  description = "IAM role name for the application (from app module output)"
  type        = string
}

# -----------------------------------------------------------------------------
# S3 CORS Configuration
# -----------------------------------------------------------------------------
variable "s3_cors_allowed_origins" {
  description = "List of allowed origins for S3 CORS configuration (presigned PUT uploads)"
  type        = list(string)
}
