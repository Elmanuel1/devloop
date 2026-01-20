# =============================================================================
# VPC Configuration - Environment Isolated (DRY)
# =============================================================================
# SECURITY BOUNDARY - Database subnets MUST NEVER have:
#   - IGW routes
#   - NAT routes
#   - Public IP assignment
#   - Load balancer attachments
# Any change to this requires security review.
#
# Network topology:
# - VPC: 10.0.0.0/16
# - Stage: public 10.0.1-2.0/24, db 10.0.20-21.0/24
# - Prod:  public 10.0.3-4.0/24, db 10.0.22-23.0/24
# =============================================================================

locals {
  environments = {
    stage = {
      public_cidrs   = var.public_subnet_cidrs_stage
      database_cidrs = var.database_subnet_cidrs_stage
    }
    prod = {
      public_cidrs   = var.public_subnet_cidrs_prod
      database_cidrs = var.database_subnet_cidrs_prod
    }
  }

  # Flatten subnets for for_each
  public_subnets = merge([
    for env, config in local.environments : {
      for idx, cidr in config.public_cidrs :
      "${env}-${idx}" => {
        env  = env
        cidr = cidr
        az   = local.azs[idx]
      }
    }
  ]...)

  database_subnets = merge([
    for env, config in local.environments : {
      for idx, cidr in config.database_cidrs :
      "${env}-${idx}" => {
        env  = env
        cidr = cidr
        az   = local.azs[idx]
      }
    }
  ]...)
}

# -----------------------------------------------------------------------------
# VPC
# -----------------------------------------------------------------------------
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "tosspaper-vpc"
  }
}

# -----------------------------------------------------------------------------
# Internet Gateway
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "tosspaper-igw"
  }
}

# -----------------------------------------------------------------------------
# Public Subnets (EC2 with public IPs)
# -----------------------------------------------------------------------------
resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id                  = aws_vpc.main.id
  cidr_block              = each.value.cidr
  availability_zone       = each.value.az
  map_public_ip_on_launch = true

  tags = {
    Name        = "tosspaper-public-${each.value.env}-${each.value.az}"
    Type        = "public"
    Environment = each.value.env
  }
}

# -----------------------------------------------------------------------------
# Database Subnets (RDS - ISOLATED, no internet)
# -----------------------------------------------------------------------------
# SECURITY: These subnets are intentionally isolated.
# Do NOT add NAT or IGW routes without security approval.
resource "aws_subnet" "database" {
  for_each = local.database_subnets

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value.cidr
  availability_zone = each.value.az

  tags = {
    Name        = "tosspaper-db-${each.value.env}-${each.value.az}"
    Type        = "database"
    Environment = each.value.env
    Internet    = "DENIED"
  }
}

# -----------------------------------------------------------------------------
# Route Tables - Per Environment
# -----------------------------------------------------------------------------

# Public route tables (with IGW)
resource "aws_route_table" "public" {
  for_each = local.environments

  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name        = "tosspaper-public-${each.key}-rt"
    Environment = each.key
  }
}

# Database route tables (ISOLATED - no routes)
# SECURITY: Intentionally empty. Do NOT add routes without approval.
resource "aws_route_table" "database" {
  for_each = local.environments

  vpc_id = aws_vpc.main.id

  # NO ROUTES - database subnets are fully isolated
  # Outbound access requires VPC endpoints (not implemented)

  tags = {
    Name        = "tosspaper-db-${each.key}-rt"
    Environment = each.key
    Internet    = "DENIED"
  }
}

# -----------------------------------------------------------------------------
# Route Table Associations
# -----------------------------------------------------------------------------
resource "aws_route_table_association" "public" {
  for_each = local.public_subnets

  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public[each.value.env].id
}

resource "aws_route_table_association" "database" {
  for_each = local.database_subnets

  subnet_id      = aws_subnet.database[each.key].id
  route_table_id = aws_route_table.database[each.value.env].id
}
