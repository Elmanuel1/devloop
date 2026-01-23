# =============================================================================
# App Module - Stage Environment
# =============================================================================

environment = "stage"
aws_region  = "us-west-2"
account_id  = "318724431231"

# Network (from terraform output in network module)
vpc_id = "vpc-00ff98d70f24c7e80"
public_subnet_ids = [
  "subnet-049058e19af62548d",
  "subnet-041668e2a59d3580e"
]

# Database (from terraform output in database module)
db_security_group_id = "sg-08aa09c8dc7ae66c0"
db_resource_id       = "db-WMYDNRPAXHDCJYMBDSHR2OJ4AE"

# SSH access (optional - set for debugging)
# ssh_key_name      = "your-key-name"
# ssh_allowed_cidrs = ["YOUR_IP/32"]

# Alerting
alert_email = "aribooluwatoba@gmail.com"
