# =============================================================================
# Outputs for Network Module - Environment Isolated
# =============================================================================

# VPC
output "vpc_id" {
  description = "ID of the VPC"
  value       = aws_vpc.main.id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC"
  value       = aws_vpc.main.cidr_block
}

# -----------------------------------------------------------------------------
# Stage Outputs
# -----------------------------------------------------------------------------
output "public_subnet_ids_stage" {
  description = "IDs of stage public subnets"
  value = [
    for k, v in aws_subnet.public : v.id
    if local.public_subnets[k].env == "stage"
  ]
}

output "database_subnet_ids_stage" {
  description = "IDs of stage database subnets"
  value = [
    for k, v in aws_subnet.database : v.id
    if local.database_subnets[k].env == "stage"
  ]
}

output "public_subnet_cidrs_stage" {
  description = "CIDR blocks of stage public subnets"
  value = [
    for k, v in aws_subnet.public : v.cidr_block
    if local.public_subnets[k].env == "stage"
  ]
}

# -----------------------------------------------------------------------------
# Prod Outputs
# -----------------------------------------------------------------------------
output "public_subnet_ids_prod" {
  description = "IDs of prod public subnets"
  value = [
    for k, v in aws_subnet.public : v.id
    if local.public_subnets[k].env == "prod"
  ]
}

output "database_subnet_ids_prod" {
  description = "IDs of prod database subnets"
  value = [
    for k, v in aws_subnet.database : v.id
    if local.database_subnets[k].env == "prod"
  ]
}

output "public_subnet_cidrs_prod" {
  description = "CIDR blocks of prod public subnets"
  value = [
    for k, v in aws_subnet.public : v.cidr_block
    if local.public_subnets[k].env == "prod"
  ]
}

# -----------------------------------------------------------------------------
# Route Tables
# -----------------------------------------------------------------------------
output "public_route_table_ids" {
  description = "IDs of public route tables by environment"
  value = {
    for k, v in aws_route_table.public : k => v.id
  }
}

output "database_route_table_ids" {
  description = "IDs of database route tables by environment"
  value = {
    for k, v in aws_route_table.database : k => v.id
  }
}

# -----------------------------------------------------------------------------
# RAM Shares
# -----------------------------------------------------------------------------
output "ram_share_arns" {
  description = "ARNs of RAM resource shares by environment"
  value = {
    for k, v in aws_ram_resource_share.env : k => v.arn
  }
}