# =============================================================================
# Variables for Network Module - Environment Isolated
# =============================================================================

variable "aws_region" {
  description = "AWS region for VPC deployment"
  type        = string
  default     = "us-west-2"
}

variable "network_account_id" {
  description = "AWS Account ID for the network account"
  type        = string
}

variable "stage_account_id" {
  description = "AWS Account ID for the stage account (for RAM sharing)"
  type        = string
}

variable "prod_account_id" {
  description = "AWS Account ID for the prod account (for RAM sharing)"
  type        = string
}

variable "security_account_id" {
  description = "AWS Account ID for the security account (for VPC Flow Logs)"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

# -----------------------------------------------------------------------------
# Stage Subnets
# -----------------------------------------------------------------------------
variable "public_subnet_cidrs_stage" {
  description = "CIDR blocks for stage public subnets"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "database_subnet_cidrs_stage" {
  description = "CIDR blocks for stage database subnets"
  type        = list(string)
  default     = ["10.0.20.0/24", "10.0.21.0/24"]
}

# -----------------------------------------------------------------------------
# Prod Subnets
# -----------------------------------------------------------------------------
variable "public_subnet_cidrs_prod" {
  description = "CIDR blocks for prod public subnets"
  type        = list(string)
  default     = ["10.0.3.0/24", "10.0.4.0/24"]
}

variable "database_subnet_cidrs_prod" {
  description = "CIDR blocks for prod database subnets"
  type        = list(string)
  default     = ["10.0.22.0/24", "10.0.23.0/24"]
}

# -----------------------------------------------------------------------------
# Optional
# -----------------------------------------------------------------------------
variable "flow_logs_bucket_arn" {
  description = "ARN of the S3 bucket in security account for VPC Flow Logs"
  type        = string
  default     = ""
}

variable "aws_profile" {
  description = "AWS profile to use for authentication"
  type        = string
  default     = null
}