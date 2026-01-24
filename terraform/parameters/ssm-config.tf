# =============================================================================
# SSM Parameter Store - Config (String)
# =============================================================================
# Reads plaintext config from JSON files and creates SSM String parameters.
# Config files are NOT encrypted (not sensitive) and committed to git.
#
# Usage:
#   terraform apply -var-file="stage.tfvars"
# =============================================================================

locals {
  config_file = "${path.module}/../../secrets/${var.environment}-config.json"
  config_data = jsondecode(file(local.config_file))
}

resource "aws_ssm_parameter" "config" {
  for_each = local.config_data

  name        = "/tosspaper/${var.environment}/config/${each.key}"
  description = "${each.key} configuration for ${var.environment} environment"
  type        = "String"
  value       = each.value

  tags = merge(local.common_tags, {
    Name       = each.key
    ConfigType = "application-config"
  })
}
