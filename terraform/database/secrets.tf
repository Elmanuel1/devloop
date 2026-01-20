# =============================================================================
# AWS Secrets Manager - Database Credentials
# =============================================================================
# Stores database credentials securely
# Application retrieves credentials at runtime using IAM role
# =============================================================================

# -----------------------------------------------------------------------------
# Secret
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "db_credentials" {
  name        = "${local.name_prefix}/database/credentials"
  description = "Database credentials for ${var.environment} environment"

  # Recovery window (days before permanent deletion)
  recovery_window_in_days = var.environment == "prod" ? 30 : 7

  tags = {
    Name        = "${local.name_prefix}-db-credentials"
    Environment = var.environment
  }
}

# -----------------------------------------------------------------------------
# Secret Value
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id

  secret_string = jsonencode({
    username = aws_db_instance.main.username
    password = random_password.db_password.result
    host     = aws_db_instance.main.address
    port     = aws_db_instance.main.port
    dbname   = aws_db_instance.main.db_name
    # JDBC URL for Spring Boot
    jdbc_url = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}"
  })

  # Note: Connection info updates when DB changes. Password is stable because
  # random_password.db_password has ignore_changes = all in rds.tf.
  # For password rotation, use RDS-managed rotation or taint the random_password.
}

# -----------------------------------------------------------------------------
# Secret Rotation (Optional - using RDS-managed rotation)
# -----------------------------------------------------------------------------
# For automatic password rotation, you can use:
# 1. RDS-managed rotation (simpler, no Lambda needed)
# 2. Lambda-based rotation (more control)
#
# RDS-managed rotation example:
# resource "aws_secretsmanager_secret_rotation" "db_password" {
#   secret_id           = aws_secretsmanager_secret.db_credentials.id
#   rotation_rules {
#     automatically_after_days = 30
#   }
# }
#
# For now, manual rotation is acceptable (documented in risk acceptances)
