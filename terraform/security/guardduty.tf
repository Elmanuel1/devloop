# =============================================================================
# AWS GuardDuty - Threat Detection
# =============================================================================
# Intelligent threat detection service that monitors for:
# - Compromised instances
# - Reconnaissance
# - Account compromise
# =============================================================================

# -----------------------------------------------------------------------------
# GuardDuty Detector
# -----------------------------------------------------------------------------
resource "aws_guardduty_detector" "main" {
  enable = true

  # Enable all data sources
  datasources {
    s3_logs {
      enable = true
    }
    kubernetes {
      audit_logs {
        enable = false # Not using EKS
      }
    }
    malware_protection {
      scan_ec2_instance_with_findings {
        ebs_volumes {
          enable = true
        }
      }
    }
  }

  # Finding publishing frequency
  finding_publishing_frequency = "FIFTEEN_MINUTES"

  tags = {
    Name    = "tosspaper-guardduty"
    Purpose = "Threat detection"
  }
}

# -----------------------------------------------------------------------------
# GuardDuty Organization Configuration
# -----------------------------------------------------------------------------
# Note: Run this from the management account to delegate administration
# This is commented out as it requires running from management account

# resource "aws_guardduty_organization_admin_account" "security" {
#   provider         = aws.management
#   admin_account_id = var.security_account_id
# }

# -----------------------------------------------------------------------------
# GuardDuty Member Accounts (for manual setup)
# -----------------------------------------------------------------------------
# If not using organization-level delegation, add members manually

# resource "aws_guardduty_member" "network" {
#   account_id  = var.network_account_id
#   detector_id = aws_guardduty_detector.main.id
#   email       = "tosspaper-network@constructdrive.com"
#   invite      = true
# }

# resource "aws_guardduty_member" "stage" {
#   account_id  = var.stage_account_id
#   detector_id = aws_guardduty_detector.main.id
#   email       = "tosspaper-stage@constructdrive.com"
#   invite      = true
# }

# resource "aws_guardduty_member" "prod" {
#   account_id  = var.prod_account_id
#   detector_id = aws_guardduty_detector.main.id
#   email       = "tosspaper-prod@constructdrive.com"
#   invite      = true
# }

# -----------------------------------------------------------------------------
# CloudWatch Event Rule for GuardDuty Findings
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_event_rule" "guardduty_findings" {
  name        = "guardduty-findings"
  description = "Capture GuardDuty findings"

  event_pattern = jsonencode({
    source      = ["aws.guardduty"]
    detail-type = ["GuardDuty Finding"]
  })

  tags = {
    Purpose = "GuardDuty alerting"
  }
}

# Log findings to CloudWatch
resource "aws_cloudwatch_log_group" "guardduty_findings" {
  name              = "/aws/events/guardduty-findings"
  retention_in_days = 30

  tags = {
    Purpose = "GuardDuty findings log"
  }
}

resource "aws_cloudwatch_event_target" "guardduty_logs" {
  rule      = aws_cloudwatch_event_rule.guardduty_findings.name
  target_id = "guardduty-to-cloudwatch"
  arn       = aws_cloudwatch_log_group.guardduty_findings.arn
}

# Allow EventBridge to write to CloudWatch Logs
resource "aws_cloudwatch_log_resource_policy" "guardduty_events" {
  policy_name = "guardduty-events-policy"

  policy_document = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "events.amazonaws.com"
        }
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.guardduty_findings.arn}:*"
      }
    ]
  })
}
