# =============================================================================
# AWS Resource Access Manager (RAM) - Environment-Isolated Subnet Sharing
# =============================================================================
# Separate RAM shares prevent cross-environment deploys:
# - Stage account → stage subnets only
# - Prod account  → prod subnets only
# =============================================================================

locals {
  # Map environment to account ID
  env_account_map = {
    stage = var.stage_account_id
    prod  = var.prod_account_id
  }
}

# -----------------------------------------------------------------------------
# RAM Resource Shares (one per environment)
# -----------------------------------------------------------------------------
resource "aws_ram_resource_share" "env" {
  for_each = local.environments

  name                      = "tosspaper-subnets-${each.key}"
  allow_external_principals = false

  tags = {
    Name        = "tosspaper-subnets-${each.key}"
    Environment = each.key
  }
}

# -----------------------------------------------------------------------------
# Share Public Subnets
# -----------------------------------------------------------------------------
resource "aws_ram_resource_association" "public" {
  for_each = local.public_subnets

  resource_arn       = aws_subnet.public[each.key].arn
  resource_share_arn = aws_ram_resource_share.env[each.value.env].arn
}

# -----------------------------------------------------------------------------
# Share Database Subnets
# -----------------------------------------------------------------------------
resource "aws_ram_resource_association" "database" {
  for_each = local.database_subnets

  resource_arn       = aws_subnet.database[each.key].arn
  resource_share_arn = aws_ram_resource_share.env[each.value.env].arn
}

# -----------------------------------------------------------------------------
# Grant Account Access (one per environment)
# -----------------------------------------------------------------------------
resource "aws_ram_principal_association" "env" {
  for_each = local.env_account_map

  principal          = each.value
  resource_share_arn = aws_ram_resource_share.env[each.key].arn
}