# =============================================================================
# App Module - Prod Environment
# =============================================================================

environment          = "prod"
aws_region           = "us-west-2"
account_id           = "" # Fill after organization apply
vpc_id               = "" # Fill after network apply
public_subnet_ids    = [] # Fill after network apply (EC2 goes in public subnet)
db_secret_arn        = "" # Fill after database apply
db_security_group_id = "" # Fill after database apply
ssh_key_name         = "" # Optional - leave empty for prod
ssh_allowed_cidrs    = [] # Leave empty for prod (no SSH access)