# =============================================================================
# EFS - Persistent File Storage
# =============================================================================
# Shared filesystem mounted at /app/files across all ASG instances
# TODO: Remove after migrating to direct S3 uploads (Issue #49)
# =============================================================================

resource "aws_efs_file_system" "app_files" {
  creation_token = "${local.name_prefix}-files"
  encrypted      = true

  performance_mode = "generalPurpose"
  throughput_mode  = "bursting"

  lifecycle_policy {
    transition_to_ia = "AFTER_30_DAYS"
  }

  tags = {
    Name = "${local.name_prefix}-files"
  }
}

# -----------------------------------------------------------------------------
# EFS Mount Targets (one per subnet)
# -----------------------------------------------------------------------------
resource "aws_efs_mount_target" "app_files" {
  count           = length(var.public_subnet_ids)
  file_system_id  = aws_efs_file_system.app_files.id
  subnet_id       = var.public_subnet_ids[count.index]
  security_groups = [aws_security_group.efs.id]
}

# -----------------------------------------------------------------------------
# EFS Security Group
# -----------------------------------------------------------------------------
resource "aws_security_group" "efs" {
  name        = "${local.name_prefix}-efs-sg"
  description = "Security group for EFS mount targets"
  vpc_id      = var.vpc_id

  ingress {
    description     = "NFS from app instances"
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-efs-sg"
  }
}
