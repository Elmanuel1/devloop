# =============================================================================
# EC2 Configuration - AMI Selection
# =============================================================================
# The actual EC2 instances are managed by the Auto Scaling Group (autoscaling.tf)
# This file defines the AMI selection for the launch template
# =============================================================================

# -----------------------------------------------------------------------------
# Latest Amazon Linux 2023 AMI (ARM64)
# -----------------------------------------------------------------------------
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-arm64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}
