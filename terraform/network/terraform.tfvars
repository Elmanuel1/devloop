# =============================================================================
# Network Module - Environment Isolated
# =============================================================================

aws_region          = "us-west-2"
network_account_id  = "096632469104"
stage_account_id    = "318724431231"
prod_account_id     = "306950916532"
security_account_id = "398980856278"

# VPC CIDR (subnets use defaults from variables.tf)
vpc_cidr = "10.0.0.0/16"

# Subnet layout (using defaults):
# Stage Public:    10.0.1.0/24, 10.0.2.0/24
# Prod Public:     10.0.3.0/24, 10.0.4.0/24
# Stage Database:  10.0.20.0/24, 10.0.21.0/24
# Prod Database:   10.0.22.0/24, 10.0.23.0/24

