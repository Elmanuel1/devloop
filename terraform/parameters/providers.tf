# =============================================================================
# Terraform & Provider Configuration - Parameters Module
# =============================================================================
# This module manages:
# - SSM Parameter Store parameters (secrets and config)
#
# Separate state file per environment:
# - terraform init -backend-config="key=parameters-stage.tfstate"
# - terraform init -backend-config="key=parameters-prod.tfstate"
#
# Run from: Stage or Prod account
# =============================================================================

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    sops = {
      source  = "carlpett/sops"
      version = "~> 1.0"
    }
  }
}

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile

  # Assume role based on environment
  assume_role {
    role_arn = "arn:aws:iam::${var.account_id}:role/OrganizationAccountAccessRole"
  }

  default_tags {
    tags = {
      Project     = "tosspaper"
      ManagedBy   = "terraform"
      Environment = var.environment
      Component   = "parameters"
    }
  }
}
