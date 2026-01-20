# =============================================================================
# Terraform Backend Configuration - Database Module
# =============================================================================
# Use -backend-config to specify the state file:
#   terraform init -backend-config="key=database-stage.tfstate"
#   terraform init -backend-config="key=database-prod.tfstate"
# =============================================================================

terraform {
  backend "s3" {
    bucket = "tosspaper-terraform-state"
    # key is set via -backend-config
    region       = "us-west-2"
    encrypt      = true
    use_lockfile = true # Native S3 locking (TF 1.10+)
  }
}