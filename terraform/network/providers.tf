# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - VPC with public, private, and database subnets
# - Single NAT Gateway (cost optimized)
# - AWS RAM sharing to workload accounts
# - VPC Flow Logs
#
# Run from: Network account (tosspaper-network)
# =============================================================================

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile

  # Assume role from management account
  assume_role {
    role_arn = "arn:aws:iam::${var.network_account_id}:role/OrganizationAccountAccessRole"
  }

  default_tags {
    tags = {
      Project     = "tosspaper"
      ManagedBy   = "terraform"
      Environment = "shared"
      Account     = "network"
    }
  }
}
