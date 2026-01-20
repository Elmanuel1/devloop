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

variable "ssh_key_name" {
  description = "Name of the SSH key pair for EC2 access"
  type        = string
  default     = "" # Optional - set for debugging access
}

variable "ssh_allowed_cidrs" {
  description = "CIDR blocks allowed to SSH into the EC2 instance. Cannot be 0.0.0.0/0."
  type        = list(string)
  default     = [] # Empty = no SSH access; set to your IP for debugging

  validation {
    condition     = !contains(var.ssh_allowed_cidrs, "0.0.0.0/0")
    error_message = "SSH access from 0.0.0.0/0 is not allowed. Use specific IP ranges."
  }
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
