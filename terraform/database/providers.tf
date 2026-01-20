# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - PostgreSQL RDS with pgvector extension
# - DB subnet group
# - Security groups
# - Secrets Manager for credentials
#
# Separate state file per environment:
# - terraform init -backend-config="key=database-stage.tfstate"
# - terraform init -backend-config="key=database-prod.tfstate"
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
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
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
      Component   = "database"
    }
  }
}
