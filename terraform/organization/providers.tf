# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - AWS Organization structure (OUs)
# - Member accounts (network, security, stage, prod)
# - Service Control Policies (SCPs)
# - GitHub OIDC provider for CI/CD
#
# Run from: Management account (ConstructDrive - 905418019159)
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
  region = var.aws_region
  # Profile is optional - only use if set (for local dev). CI uses env vars from OIDC.
  profile = var.aws_profile

  default_tags {
    tags = {
      Project     = "tosspaper"
      ManagedBy   = "terraform"
      Environment = "management"
      Repository  = var.github_repository
    }
  }
}
