# =============================================================================
# Variables for AWS Organizations Module
# =============================================================================

variable "aws_region" {
  description = "AWS region for provider configuration"
  type        = string
}

variable "organization_root_id" {
  description = "Root ID of the AWS Organization (e.g., r-axqz)"
  type        = string
}

variable "github_org" {
  description = "GitHub organization name"
  type        = string
}

variable "github_repository" {
  description = "GitHub repository name (without org prefix)"
  type        = string
}

variable "account_email_domain" {
  description = "Domain for account root emails"
  type        = string
}

variable "allowed_regions" {
  description = "List of AWS regions to allow via SCP"
  type        = list(string)
}

variable "aws_profile" {
  description = "AWS profile to use for authentication (optional, defaults to AWS_PROFILE env var or default profile)"
  type        = string
  default     = null
}
