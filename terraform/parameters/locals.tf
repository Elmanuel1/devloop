# =============================================================================
# SSM Parameter Store Configuration
# =============================================================================
# Secrets: Encrypted with SOPS (AGE), decrypted at terraform apply time
# Config:  Plaintext JSON files (not encrypted, committed to git)
#
# See docs/secrets-management.md for the SOPS workflow.
# =============================================================================

locals {
  # Resource naming
  name_prefix = "tosspaper-${var.environment}"

  # Common tags for parameters
  common_tags = {
    Project     = "tosspaper"
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # Extract secret keys only (names are not sensitive, values are)
  secret_keys = nonsensitive(toset(keys(data.sops_file.secrets.data)))
}
