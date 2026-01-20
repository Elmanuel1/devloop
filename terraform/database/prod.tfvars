# =============================================================================
# Database Module - Prod Environment
# =============================================================================

environment = "prod"
aws_region  = "us-west-2"
account_id  = "306950916532"

# Network (from terraform output in network module)
vpc_id = "vpc-00ff98d70f24c7e80"
database_subnet_ids = [
  "subnet-0a25328ad1a1a9c23",
  "subnet-00a9d32f9a6123473"
]
public_subnet_cidrs = [
  "10.0.3.0/24",
  "10.0.4.0/24"
]

# Alerts
alert_email = "aribooluwatoba@gmail.com"