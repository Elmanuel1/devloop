# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - S3 bucket (tender document uploads with presigned URLs)
# - SQS queue + DLQ (S3 ObjectCreated event notifications)
# - IAM policy for application access (s3 + sqs)
#
# Separate state file per environment:
# - terraform init -backend-config="key=precon-stage.tfstate"
# - terraform init -backend-config="key=precon-prod.tfstate"
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
      Component   = "precon"
    }
  }
}
