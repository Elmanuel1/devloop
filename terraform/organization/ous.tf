# =============================================================================
# AWS Organizations - Organizational Units (OUs)
# =============================================================================
# Structure:
#   Root (r-axqz)
#   └── Tosspaper OU
#       ├── Tosspaper-Infrastructure OU
#       │   ├── tosspaper-network
#       │   └── tosspaper-security
#       └── Tosspaper-Workloads OU
#           ├── tosspaper-stage
#           └── tosspaper-prod
# =============================================================================

# Tosspaper root OU - contains all tosspaper accounts
resource "aws_organizations_organizational_unit" "tosspaper" {
  name      = "Tosspaper"
  parent_id = var.organization_root_id

  tags = {
    Description = "Root OU for all Tosspaper accounts"
  }
}

# Infrastructure OU - network and security accounts
resource "aws_organizations_organizational_unit" "infrastructure" {
  name      = "Infrastructure"
  parent_id = aws_organizations_organizational_unit.tosspaper.id
}

# Workloads OU - stage and prod accounts
resource "aws_organizations_organizational_unit" "workloads" {
  name      = "Workloads"
  parent_id = aws_organizations_organizational_unit.tosspaper.id
}
