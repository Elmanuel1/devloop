# =============================================================================
# Terraform Backend Configuration - Security Module
# =============================================================================

terraform {
  backend "s3" {
    bucket       = "tosspaper-terraform-state"
    key          = "security.tfstate"
    region       = "us-west-2"
    encrypt      = true
    use_lockfile = true # Native S3 locking (TF 1.10+)
  }
}