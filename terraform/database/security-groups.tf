# =============================================================================
# Security Groups for Database
# =============================================================================

resource "aws_security_group" "db" {
  name        = "${local.name_prefix}-db-sg"
  description = "Security group for RDS PostgreSQL"
  vpc_id      = var.vpc_id

  # Ingress rules are managed by the app module (app_to_db rule)
  # This ensures least-privilege: only app instances can access DB

  # No egress needed for RDS (it doesn't initiate connections)
  # But AWS requires at least one egress rule
  egress {
    description = "Allow outbound (required by AWS)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-db-sg"
  }

  lifecycle {
    create_before_destroy = true
    # Ignore ingress rules added by other modules (e.g., app module adds app_to_db rule)
    ignore_changes = [ingress]
  }
}
