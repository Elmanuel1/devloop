# =============================================================================
# Outputs - Parameters Module
# =============================================================================

output "secrets_parameter_names" {
  description = "List of secret parameter names"
  value       = [for p in aws_ssm_parameter.secrets : p.name]
}

output "config_parameter_names" {
  description = "List of config parameter names"
  value       = [for p in aws_ssm_parameter.config : p.name]
}

output "secrets_path" {
  description = "Path prefix for secrets parameters"
  value       = "/tosspaper/${var.environment}/secrets/"
}

output "config_path" {
  description = "Path prefix for config parameters"
  value       = "/tosspaper/${var.environment}/config/"
}

output "secrets_source" {
  description = "Source of secrets (SOPS encrypted file)"
  value       = "secrets/${var.environment}-secrets.enc.json"
}

output "config_source" {
  description = "Source of config (plaintext JSON file)"
  value       = "secrets/${var.environment}-config.json"
}
