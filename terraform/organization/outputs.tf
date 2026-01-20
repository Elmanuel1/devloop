# =============================================================================
# Outputs for AWS Organizations Module
# =============================================================================

# Account IDs - used by downstream modules
output "network_account_id" {
  description = "AWS Account ID for the network account"
  value       = aws_organizations_account.network.id
}

output "security_account_id" {
  description = "AWS Account ID for the security account"
  value       = aws_organizations_account.security.id
}

output "stage_account_id" {
  description = "AWS Account ID for the stage account"
  value       = aws_organizations_account.stage.id
}

output "prod_account_id" {
  description = "AWS Account ID for the prod account"
  value       = aws_organizations_account.prod.id
}

# OU IDs
output "tosspaper_ou_id" {
  description = "Tosspaper root OU ID"
  value       = aws_organizations_organizational_unit.tosspaper.id
}

output "infrastructure_ou_id" {
  description = "Infrastructure OU ID"
  value       = aws_organizations_organizational_unit.infrastructure.id
}

output "workloads_ou_id" {
  description = "Workloads OU ID"
  value       = aws_organizations_organizational_unit.workloads.id
}

# GitHub OIDC
output "github_oidc_role_arn" {
  description = "ARN of the IAM role for GitHub Actions"
  value       = aws_iam_role.github_actions.arn
}

output "github_oidc_provider_arn" {
  description = "ARN of the GitHub OIDC provider"
  value       = aws_iam_openid_connect_provider.github.arn
}

# Management account info
output "management_account_id" {
  description = "Management account ID (ConstructDrive)"
  value       = data.aws_caller_identity.current.account_id
}
