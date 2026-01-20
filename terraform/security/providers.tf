# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - CloudTrail (organization-wide audit trail)
# - GuardDuty (threat detection)
# - Security Hub (security findings aggregation)
# - Log Archive S3 bucket (central log storage)
#
# Run from: Security account (tosspaper-security)
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
    role_arn = "arn:aws:iam::${var.security_account_id}:role/OrganizationAccountAccessRole"
  }

  default_tags {
    tags = {
      Project     = "tosspaper"
      ManagedBy   = "terraform"
      Environment = "security"
      Account     = "security"
    }
  }
}

# Provider for management account (for org-wide CloudTrail)
provider "aws" {
  alias   = "management"
  region  = var.aws_region
  profile = var.aws_profile

  default_tags {
    tags = {
      Project   = "tosspaper"
      ManagedBy = "terraform"
    }
  }
}
