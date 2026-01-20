# =============================================================================
# GitHub OIDC Provider and IAM Role
# =============================================================================
# Enables GitHub Actions to authenticate with AWS without long-lived credentials
#
# Security restrictions:
# - aud: Must be sts.amazonaws.com (prevents token reuse)
# - sub: Restricts to specific repo + branch/environment
# - No wildcard on repository
# =============================================================================

# GitHub OIDC Provider
resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = ["sts.amazonaws.com"]

  # GitHub's OIDC thumbprints (include both for certificate rotation)
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
    "1b511abead59c6ce207077c0bf0e0043b1382612"
  ]

  tags = {
    Purpose = "GitHub Actions OIDC authentication"
  }
}

# IAM Role for GitHub Actions
resource "aws_iam_role" "github_actions" {
  name        = "GitHubActionsRole"
  description = "Role assumed by GitHub Actions for Terraform deployments"

  # Trust policy - restricts which GitHub repos/branches can assume this role
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }
          StringLike = {
            "token.actions.githubusercontent.com:sub" = [
              # Main branch - for workflow_dispatch (plan/apply)
              "repo:${var.github_org}/${var.github_repository}:ref:refs/heads/main",
              # Pull requests - for PR validation
              "repo:${var.github_org}/${var.github_repository}:pull_request",
              # GitHub environments (must match workflow environment names)
              "repo:${var.github_org}/${var.github_repository}:environment:prod",
              "repo:${var.github_org}/${var.github_repository}:environment:stage"
            ]
          }
        }
      }
    ]
  })

  # Maximum session duration (1 hour)
  max_session_duration = 3600

  tags = {
    Purpose        = "Terraform CI/CD"
    TosspaperAdmin = "true" # Required for SCP exceptions
  }
}

# Policy: Allow assuming OrganizationAccountAccessRole in member accounts
resource "aws_iam_role_policy" "github_actions_assume_role" {
  name = "AssumeOrganizationAccountAccessRole"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AssumeRoleInMemberAccounts"
        Effect = "Allow"
        Action = "sts:AssumeRole"
        Resource = [
          "arn:aws:iam::${aws_organizations_account.network.id}:role/OrganizationAccountAccessRole",
          "arn:aws:iam::${aws_organizations_account.security.id}:role/OrganizationAccountAccessRole",
          "arn:aws:iam::${aws_organizations_account.stage.id}:role/OrganizationAccountAccessRole",
          "arn:aws:iam::${aws_organizations_account.prod.id}:role/OrganizationAccountAccessRole"
        ]
      }
    ]
  })
}

# Policy: Terraform state management in management account
resource "aws_iam_role_policy" "github_actions_terraform_state" {
  name = "TerraformStateAccess"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3StateAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::tosspaper-terraform-state",
          "arn:aws:s3:::tosspaper-terraform-state/*"
        ]
      }
    ]
  })
}

# Policy: Organizations management (for organization module only)
# NOTE: Organizations API doesn't support resource-level permissions for most actions
resource "aws_iam_role_policy" "github_actions_organizations" {
  name = "OrganizationsManagement"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "OrganizationsReadOnly"
        Effect = "Allow"
        Action = [
          "organizations:Describe*",
          "organizations:List*"
        ]
        Resource = "*"
      },
      {
        Sid    = "OrganizationsOUAndPolicyManagement"
        Effect = "Allow"
        Action = [
          # OU management (less dangerous than account creation)
          "organizations:CreateOrganizationalUnit",
          "organizations:UpdateOrganizationalUnit",
          "organizations:DeleteOrganizationalUnit",
          # SCP management
          "organizations:CreatePolicy",
          "organizations:AttachPolicy",
          "organizations:DetachPolicy",
          "organizations:UpdatePolicy",
          "organizations:DeletePolicy",
          # Tagging
          "organizations:TagResource",
          "organizations:UntagResource"
        ]
        Resource = "*"
      },
      {
        Sid    = "OrganizationsAccountManagement"
        Effect = "Allow"
        Action = [
          # Account operations - CREATE ACCOUNTS ONCE, then manage
          "organizations:CreateAccount",
          "organizations:MoveAccount"
          # NOTE: CloseAccount intentionally omitted - irreversible
        ]
        Resource = "*"
        # IMPORTANT: Use with caution. Consider removing CreateAccount
        # after initial setup and requiring manual account creation.
      },
      {
        Sid    = "DenyDangerousOrganizationsActions"
        Effect = "Deny"
        Action = [
          # Never allow these via automation
          "organizations:LeaveOrganization",
          "organizations:DeleteOrganization",
          "organizations:CloseAccount",
          "organizations:RemoveAccountFromOrganization"
        ]
        Resource = "*"
      },
      {
        Sid    = "IAMForOIDCAndBreakGlass"
        Effect = "Allow"
        Action = [
          "iam:CreateOpenIDConnectProvider",
          "iam:DeleteOpenIDConnectProvider",
          "iam:GetOpenIDConnectProvider",
          "iam:UpdateOpenIDConnectProviderThumbprint",
          "iam:TagOpenIDConnectProvider",
          "iam:CreateRole",
          "iam:DeleteRole",
          "iam:GetRole",
          "iam:UpdateRole",
          "iam:TagRole",
          "iam:AttachRolePolicy",
          "iam:DetachRolePolicy",
          "iam:PutRolePolicy",
          "iam:DeleteRolePolicy",
          "iam:GetRolePolicy",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies"
        ]
        Resource = [
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/GitHubActionsRole",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/TosspaperBreakGlassRole",
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
        ]
      }
    ]
  })
}

# =============================================================================
# Policy: SSM Run Command for Flyway migrations and deployments
# =============================================================================
# Allows GitHub Actions to execute commands on EC2 instances via SSM.
# This enables running Flyway migrations against RDS (which is in private subnets)
# by executing commands on EC2 instances that have direct RDS access.
#
# Architecture:
#   GitHub Actions (OIDC) → SSM Run Command → EC2 → RDS
#
# Usage (in GitHub Actions):
#   aws ssm send-command \
#     --instance-ids "i-xxx" \
#     --document-name "AWS-RunShellScript" \
#     --parameters 'commands=["flyway migrate"]'
# =============================================================================
resource "aws_iam_role_policy" "github_actions_ssm" {
  name = "SSMRunCommand"
  role = aws_iam_role.github_actions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SSMSendCommand"
        Effect = "Allow"
        Action = [
          "ssm:SendCommand",
          "ssm:GetCommandInvocation"
        ]
        Resource = [
          # Allow sending commands to EC2 instances in stage/prod accounts
          "arn:aws:ec2:${var.aws_region}:${aws_organizations_account.stage.id}:instance/*",
          "arn:aws:ec2:${var.aws_region}:${aws_organizations_account.prod.id}:instance/*",
          # SSM document - AWS-RunShellScript is built-in
          "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
        ]
      },
      {
        Sid    = "SSMGetCommandInvocation"
        Effect = "Allow"
        Action = [
          "ssm:GetCommandInvocation"
        ]
        Resource = "*"
      }
    ]
  })
}

# =============================================================================
# Break-Glass IAM Role
# =============================================================================
# Emergency access role for when OIDC or SCPs lock you out
# Protected by MFA requirement - should be assumed manually from console
# =============================================================================

resource "aws_iam_role" "break_glass" {
  name        = "TosspaperBreakGlassRole"
  description = "Emergency access role - use only when OIDC/SCPs lock you out"

  # Trust policy - allows root account to assume (with MFA)
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowRootWithMFA"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action = "sts:AssumeRole"
        Condition = {
          Bool = {
            "aws:MultiFactorAuthPresent" = "true"
          }
        }
      }
    ]
  })

  # Maximum session duration (1 hour - short for emergency use)
  max_session_duration = 3600

  tags = {
    Purpose        = "Emergency break-glass access"
    TosspaperAdmin = "true" # Required for SCP exceptions
    BreakGlass     = "true"
  }
}

# Break-glass policy - admin access to Organizations and IAM
resource "aws_iam_role_policy" "break_glass_admin" {
  name = "BreakGlassAdmin"
  role = aws_iam_role.break_glass.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "OrganizationsAdmin"
        Effect   = "Allow"
        Action   = "organizations:*"
        Resource = "*"
      },
      {
        Sid      = "IAMAdmin"
        Effect   = "Allow"
        Action   = "iam:*"
        Resource = "*"
      },
      {
        Sid    = "AssumeRoleInMemberAccounts"
        Effect = "Allow"
        Action = "sts:AssumeRole"
        Resource = [
          "arn:aws:iam::*:role/OrganizationAccountAccessRole"
        ]
      },
      {
        Sid    = "S3StateAccess"
        Effect = "Allow"
        Action = "s3:*"
        Resource = [
          "arn:aws:s3:::tosspaper-terraform-state",
          "arn:aws:s3:::tosspaper-terraform-state/*"
        ]
      }
    ]
  })
}
