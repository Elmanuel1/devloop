# =============================================================================
# Variables for Security Module
# =============================================================================

variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "security_account_id" {
  description = "AWS Account ID for the security account"
  type        = string
}

variable "management_account_id" {
  description = "AWS Account ID for the management account"
  type        = string
}

variable "network_account_id" {
  description = "AWS Account ID for the network account"
  type        = string
}

variable "stage_account_id" {
  description = "AWS Account ID for the stage account"
  type        = string
}

variable "prod_account_id" {
  description = "AWS Account ID for the prod account"
  type        = string
}

variable "log_retention_days" {
  description = "Number of days to retain logs in S3"
  type        = number
  default     = 365 # 1 year for compliance
}

variable "flow_logs_retention_days" {
  description = "Number of days to retain VPC Flow Logs"
  type        = number
  default     = 90
}

variable "aws_profile" {
  description = "AWS profile to use for authentication"
  type        = string
  default     = null
}
