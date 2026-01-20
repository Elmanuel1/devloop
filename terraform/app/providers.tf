# =============================================================================
# Terraform & Provider Configuration
# =============================================================================
# This module manages:
# - EC2 instance (application server)
# - IAM role and instance profile
# - S3 bucket (email attachments)
# - SQS queues with DLQs
# - Security groups
# - CloudWatch logs and alarms
#
# Separate state file per environment:
# - terraform init -backend-config="key=app-stage.tfstate"
# - terraform init -backend-config="key=app-prod.tfstate"
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
      Component   = "application"
    }
  }
}
