# =============================================================================
# CloudWatch Alarms for RDS (Prod only)
# =============================================================================

# -----------------------------------------------------------------------------
# SNS Topic for Alerts
# -----------------------------------------------------------------------------
resource "aws_sns_topic" "db_alerts" {
  count = var.environment == "prod" && var.alert_email != "" ? 1 : 0

  name = "${local.name_prefix}-db-alerts"

  tags = {
    Name = "${local.name_prefix}-db-alerts"
  }
}

resource "aws_sns_topic_subscription" "email" {
  count = var.environment == "prod" && var.alert_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.db_alerts[0].arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = var.environment == "prod" && var.alert_email != "" ? [aws_sns_topic.db_alerts[0].arn] : []
}

# -----------------------------------------------------------------------------
# CPU Utilization
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU utilization > 80%"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-cpu-alarm"
  }
}

# -----------------------------------------------------------------------------
# Free Storage Space
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_storage" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 10737418240 # 10 GB in bytes
  alarm_description   = "RDS free storage < 10GB"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-storage-alarm"
  }
}

# -----------------------------------------------------------------------------
# Database Connections
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_connections" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-connections-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "DatabaseConnections"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80 # db.t4g.medium max ~100 connections
  alarm_description   = "RDS connections > 80"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-connections-alarm"
  }
}

# -----------------------------------------------------------------------------
# Read Latency
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_read_latency" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-read-latency-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ReadLatency"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 0.02 # 20ms
  alarm_description   = "RDS read latency > 20ms"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-read-latency-alarm"
  }
}

# -----------------------------------------------------------------------------
# Write Latency
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_write_latency" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-write-latency-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "WriteLatency"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 0.05 # 50ms
  alarm_description   = "RDS write latency > 50ms"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-write-latency-alarm"
  }
}

# -----------------------------------------------------------------------------
# Freeable Memory
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "db_memory" {
  count = var.environment == "prod" ? 1 : 0

  alarm_name          = "${local.name_prefix}-db-memory-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 3
  metric_name         = "FreeableMemory"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 268435456 # 256 MB in bytes
  alarm_description   = "RDS freeable memory < 256MB"
  alarm_actions       = local.alarm_actions

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }

  tags = {
    Name = "${local.name_prefix}-db-memory-alarm"
  }
}