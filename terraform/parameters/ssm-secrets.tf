# =============================================================================
# SSM Parameter Store - Secrets (SecureString)
# =============================================================================
# Decrypts SOPS-encrypted secrets and creates SSM SecureString parameters.
#
# Workflow:
#   1. Edit secrets/stage-secrets.json (plaintext, gitignored)
#   2. Encrypt: sops --encrypt secrets/stage-secrets.json > secrets/stage-secrets.enc.json
#   3. Commit encrypted file
#   4. terraform apply (decrypts automatically)
#
# CI/CD: Set SOPS_AGE_KEY environment variable from GitHub secrets
# =============================================================================

data "sops_file" "secrets" {
  source_file = "${path.module}/../../secrets/${var.environment}-secrets.enc.json"
}

resource "aws_ssm_parameter" "secrets" {
  for_each = local.secret_keys

  name        = "/tosspaper/${var.environment}/secrets/${each.key}"
  description = "${each.key} for ${var.environment} environment"
  type        = "SecureString"
  value       = data.sops_file.secrets.data[each.key]

  tags = merge(local.common_tags, {
    Name       = each.key
    SecretType = "application-secret"
  })
}
