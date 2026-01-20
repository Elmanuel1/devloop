# =============================================================================
# Terraform Backend Configuration
# =============================================================================
# State stored in S3 with native S3 locking (Terraform 1.10+)
# No DynamoDB required - uses S3 conditional writes for lock files
#
# Bootstrap (run once manually before first apply):
#   aws s3 mb s3://tosspaper-terraform-state --region us-west-2
#   aws s3api put-bucket-versioning --bucket tosspaper-terraform-state \
#     --versioning-configuration Status=Enabled
#   aws s3api put-bucket-encryption --bucket tosspaper-terraform-state \
#     --server-side-encryption-configuration \
#     '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
# =============================================================================

terraform {
  backend "s3" {
    bucket       = "tosspaper-terraform-state"
    key          = "organization.tfstate"
    region       = "us-west-2"
    encrypt      = true
    use_lockfile = true # Native S3 locking (TF 1.10+), creates .tflock file
  }
}
