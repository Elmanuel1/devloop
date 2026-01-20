# =============================================================================
# AWS Security Hub
# =============================================================================
# Aggregates security findings from:
# - GuardDuty
# - IAM Access Analyzer
# - Inspector
# - Third-party tools
#
# Provides compliance checks against security standards
# =============================================================================

# -----------------------------------------------------------------------------
# Security Hub
# -----------------------------------------------------------------------------
resource "aws_securityhub_account" "main" {
  # Enable auto-enable for new controls
  auto_enable_controls = true
}

# -----------------------------------------------------------------------------
# Security Standards
# -----------------------------------------------------------------------------

# AWS Foundational Security Best Practices
resource "aws_securityhub_standards_subscription" "aws_foundational" {
  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/aws-foundational-security-best-practices/v/1.0.0"

  depends_on = [aws_securityhub_account.main]
}

# CIS AWS Foundations Benchmark
resource "aws_securityhub_standards_subscription" "cis" {
  standards_arn = "arn:aws:securityhub:${var.aws_region}::standards/cis-aws-foundations-benchmark/v/1.4.0"

  depends_on = [aws_securityhub_account.main]
}

# -----------------------------------------------------------------------------
# Security Hub Organization Configuration
# -----------------------------------------------------------------------------
# Note: Run this from the management account to delegate administration
# This is commented out as it requires running from management account

# resource "aws_securityhub_organization_admin_account" "security" {
#   provider         = aws.management
#   admin_account_id = var.security_account_id
# }

# -----------------------------------------------------------------------------
# Security Hub Member Accounts (for manual setup)
# -----------------------------------------------------------------------------
# If not using organization-level delegation, add members manually

# resource "aws_securityhub_member" "network" {
#   account_id = var.network_account_id
#   email      = "tosspaper-network@constructdrive.com"
#   invite     = true
#
#   depends_on = [aws_securityhub_account.main]
# }

# resource "aws_securityhub_member" "stage" {
#   account_id = var.stage_account_id
#   email      = "tosspaper-stage@constructdrive.com"
#   invite     = true
#
#   depends_on = [aws_securityhub_account.main]
# }

# resource "aws_securityhub_member" "prod" {
#   account_id = var.prod_account_id
#   email      = "tosspaper-prod@constructdrive.com"
#   invite     = true
#
#   depends_on = [aws_securityhub_account.main]
# }

# -----------------------------------------------------------------------------
# CloudWatch Event Rule for Critical Findings
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_event_rule" "securityhub_critical" {
  name        = "securityhub-critical-findings"
  description = "Capture critical and high severity Security Hub findings"

  event_pattern = jsonencode({
    source      = ["aws.securityhub"]
    detail-type = ["Security Hub Findings - Imported"]
    detail = {
      findings = {
        Severity = {
          Label = ["CRITICAL", "HIGH"]
        }
      }
    }
  })

  tags = {
    Purpose = "Security Hub alerting"
  }
}

# Log findings to CloudWatch
resource "aws_cloudwatch_log_group" "securityhub_findings" {
  name              = "/aws/events/securityhub-findings"
  retention_in_days = 30

  tags = {
    Purpose = "Security Hub findings log"
  }
}

resource "aws_cloudwatch_event_target" "securityhub_logs" {
  rule      = aws_cloudwatch_event_rule.securityhub_critical.name
  target_id = "securityhub-to-cloudwatch"
  arn       = aws_cloudwatch_log_group.securityhub_findings.arn
}
