# =============================================================================
# App Module - Prod Environment
# =============================================================================

environment = "prod"
aws_region  = "us-west-2"
account_id  = "306950916532"

# Network (from terraform output in network module)
vpc_id = "vpc-00ff98d70f24c7e80"
public_subnet_ids = [
  "subnet-0dd78249a92acf014",
  "subnet-0c41b5522dea8acd9"
]

# Database (from terraform output in database module)
db_security_group_id = "sg-01dbf57fb62032657"
db_resource_id       = "db-LBWWFBSF6A34PKBIMFJN63M6XM"

# Alerting
alert_email = "aribooluwatoba@gmail.com"

# S3 CORS allowed origins
s3_cors_allowed_origins = ["https://app.tosspaper.com", "https://www.tosspaper.com", "https://tosspaper.com"]
