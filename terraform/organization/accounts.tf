# =============================================================================
# AWS Organizations - Member Accounts
# =============================================================================
# Creates 4 accounts under the Tosspaper OUs:
# - tosspaper-network: Shared VPC, NAT Gateway
# - tosspaper-security: CloudTrail, GuardDuty, Security Hub, Logs
# - tosspaper-stage: Staging workloads
# - tosspaper-prod: Production workloads
# =============================================================================

# Network Account - Shared VPC infrastructure
resource "aws_organizations_account" "network" {
  name      = "tosspaper-network"
  email     = "network@${var.account_email_domain}"
  parent_id = aws_organizations_organizational_unit.infrastructure.id

  # Deny IAM users billing access - use management account for cost review
  iam_user_access_to_billing = "DENY"

  # Prevent accidental deletion
  close_on_deletion = false

  tags = {
    Purpose     = "Shared VPC and networking"
    AccountType = "infrastructure"
  }

  lifecycle {
    # Email cannot be changed after account creation
    ignore_changes = [email]
  }
}

# Security Account - Centralized security and logging
resource "aws_organizations_account" "security" {
  name      = "tosspaper-security"
  email     = "security@${var.account_email_domain}"
  parent_id = aws_organizations_organizational_unit.infrastructure.id

  iam_user_access_to_billing = "DENY"
  close_on_deletion          = false

  tags = {
    Purpose     = "Security monitoring and log archive"
    AccountType = "infrastructure"
  }

  lifecycle {
    ignore_changes = [email]
  }
}

# Stage Account - Staging workloads
resource "aws_organizations_account" "stage" {
  name      = "tosspaper-stage"
  email     = "stage@${var.account_email_domain}"
  parent_id = aws_organizations_organizational_unit.workloads.id

  iam_user_access_to_billing = "DENY"
  close_on_deletion          = false

  tags = {
    Purpose     = "Staging environment"
    AccountType = "workload"
    Environment = "stage"
  }

  lifecycle {
    ignore_changes = [email]
  }
}

# Prod Account - Production workloads
resource "aws_organizations_account" "prod" {
  name      = "tosspaper-prod"
  email     = "prod@${var.account_email_domain}"
  parent_id = aws_organizations_organizational_unit.workloads.id

  iam_user_access_to_billing = "DENY"
  close_on_deletion          = false

  tags = {
    Purpose     = "Production environment"
    AccountType = "workload"
    Environment = "prod"
  }

  lifecycle {
    ignore_changes = [email]
  }
}
