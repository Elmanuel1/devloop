# =============================================================================
# Database Module - Stage Environment
# =============================================================================

environment = "stage"
aws_region  = "us-west-2"
account_id  = "318724431231"

# Network (from terraform output in network module)
vpc_id = "vpc-00ff98d70f24c7e80"
database_subnet_ids = [
  "subnet-08ec6b56e9bebeee8",
  "subnet-0c9c979a6c9abd4c0"
]
public_subnet_cidrs = [
  "10.0.1.0/24",
  "10.0.2.0/24"
]