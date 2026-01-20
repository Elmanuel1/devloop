# =============================================================================
# App Module - Stage Environment
# =============================================================================

environment          = "stage"
aws_region           = "us-west-2"
account_id           = "" # Fill after organization apply
vpc_id               = "" # Fill after network apply
public_subnet_ids    = [] # Fill after network apply (EC2 goes in public subnet)
db_secret_arn        = "" # Fill after database apply
db_security_group_id = "" # Fill after database apply
ssh_key_name         = "" # Optional - for debugging
ssh_allowed_cidrs    = [] # Set to your IP for SSH access (e.g., ["1.2.3.4/32"])