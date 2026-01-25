# =============================================================================
# Security Groups for Application
# =============================================================================
# Traffic flow: Cloudflare -> ALB -> EC2
# - ALB security group allows Cloudflare IPs (defined in alb.tf)
# - App security group allows ALB only
# =============================================================================

# Cloudflare IPv4 ranges (from https://www.cloudflare.com/ips-v4/)
# Used by ALB security group in alb.tf
locals {
  cloudflare_ipv4_cidrs = [
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
  ]
}

# -----------------------------------------------------------------------------
# Application Security Group (EC2 instances)
# -----------------------------------------------------------------------------
# Traffic from ALB only (no direct Cloudflare access)
# -----------------------------------------------------------------------------
resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app-sg"
  description = "Security group for application server (Cloudflare only)"
  vpc_id      = var.vpc_id

  # Ingress: HTTP from ALB only (TLS terminated at ALB)
  ingress {
    description     = "HTTP from ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Egress: Allow all outbound
  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-app-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Allow app to access database
# -----------------------------------------------------------------------------
resource "aws_security_group_rule" "app_to_db" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = var.db_security_group_id
  source_security_group_id = aws_security_group.app.id
  description              = "Allow app to access database"
}