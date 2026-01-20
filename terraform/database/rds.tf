# =============================================================================
# RDS PostgreSQL with pgvector Extension
# =============================================================================
# PostgreSQL 17 with pgvector for vector embeddings
# Single-AZ deployment (see risk acceptances for prod)
# =============================================================================

# -----------------------------------------------------------------------------
# KMS Key for RDS Encryption (Performance Insights)
# -----------------------------------------------------------------------------
resource "aws_kms_key" "rds" {
  description             = "KMS key for RDS Performance Insights - ${var.environment}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = {
    Name = "${local.name_prefix}-rds-kms"
  }
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${local.name_prefix}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

# -----------------------------------------------------------------------------
# DB Subnet Group
# -----------------------------------------------------------------------------
resource "aws_db_subnet_group" "main" {
  name        = "${local.name_prefix}-db-subnet-group"
  description = "Database subnet group for ${var.environment}"
  subnet_ids  = var.database_subnet_ids

  tags = {
    Name = "${local.name_prefix}-db-subnet-group"
  }
}

# -----------------------------------------------------------------------------
# DB Parameter Group (PostgreSQL 16 with pgvector)
# -----------------------------------------------------------------------------
resource "aws_db_parameter_group" "main" {
  name_prefix = "${local.name_prefix}-pg17-"
  family      = "postgres17"
  description = "PostgreSQL 17 parameters for ${var.environment}"

  # Require SSL connections
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  # Log settings for debugging (reduce in prod if too verbose)
  parameter {
    name  = "log_statement"
    value = local.is_lower_env ? "all" : "ddl"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = local.is_lower_env ? "0" : "100" # Log slow queries > 1s in prod
  }

  # Enable pgvector extension loading
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  tags = {
    Name = "${local.name_prefix}-pg17-params"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# RDS Instance
# -----------------------------------------------------------------------------
resource "aws_db_instance" "main" {
  identifier = "${local.name_prefix}-postgres"

  # Engine
  engine               = "postgres"
  engine_version       = "17.7"
  instance_class       = local.config.instance_class
  parameter_group_name = aws_db_parameter_group.main.name

  # Storage
  allocated_storage     = local.config.allocated_storage
  max_allocated_storage = local.config.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  # Database
  db_name  = var.db_name
  username = var.db_username
  password = random_password.db_password.result

  # Network
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  port                   = 5432

  # IAM Database Authentication
  # Enables passwordless authentication using IAM roles
  iam_database_authentication_enabled = true

  # Availability
  multi_az = local.config.multi_az

  # Backup & Recovery
  backup_retention_period   = local.config.backup_retention_period
  backup_window             = local.config.backup_window
  maintenance_window        = local.config.maintenance_window
  copy_tags_to_snapshot     = true
  delete_automated_backups  = local.is_lower_env
  final_snapshot_identifier = local.config.skip_final_snapshot ? null : "${local.name_prefix}-final-snapshot"
  skip_final_snapshot       = local.config.skip_final_snapshot

  # Protection
  deletion_protection = local.config.deletion_protection

  # Monitoring
  performance_insights_enabled          = true
  performance_insights_retention_period = 7 # Free tier
  performance_insights_kms_key_id       = aws_kms_key.rds.arn

  # Updates
  auto_minor_version_upgrade = true
  apply_immediately          = local.is_lower_env

  tags = {
    Name = "${local.name_prefix}-postgres"
  }

  lifecycle {
    # Prevent accidental deletion (set to false temporarily if destroy needed)
    prevent_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Random Password for Database
# -----------------------------------------------------------------------------
resource "random_password" "db_password" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"

  lifecycle {
    # Don't regenerate password on every apply
    ignore_changes = all
  }
}
