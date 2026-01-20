# =============================================================================
# Organization Module - Management Account
# =============================================================================

aws_region           = "us-west-2"
organization_root_id = "r-axqz"       # Get from: aws organizations list-roots --query 'Roots[0].Id' --output text
github_org           = "Build4Africa" # Your GitHub org name
github_repository    = "tosspaper"
account_email_domain = "constructdrive.com"
allowed_regions      = ["us-west-2"]
