# =============================================================================
# Database Module - Prod Environment
# =============================================================================

environment         = "prod"
aws_region          = "us-west-2"
account_id          = ""                             # Fill after organization apply
vpc_id              = ""                             # Fill after network apply
database_subnet_ids = []                             # Fill after network apply
public_subnet_cidrs = ["10.0.1.0/24", "10.0.2.0/24"] # EC2 subnets (for security group)
db_name             = "tosspaper"
db_username         = "postgres"