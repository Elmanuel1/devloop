# =============================================================================
# Account-Level Defaults
# =============================================================================
# These settings apply to the entire account, not individual resources.
# More reliable than SCPs for encryption and metadata service enforcement.
# =============================================================================

# -----------------------------------------------------------------------------
# EBS Encryption by Default
# -----------------------------------------------------------------------------
# Forces ALL new EBS volumes to be encrypted (root volumes, data volumes, etc.)
# More reliable than SCP condition "ec2:Encrypted" which can be bypassed
resource "aws_ebs_encryption_by_default" "enabled" {
  enabled = true
}

# -----------------------------------------------------------------------------
# EC2 Instance Metadata Service Defaults (IMDSv2 Required)
# -----------------------------------------------------------------------------
# Forces all NEW EC2 instances to require IMDSv2 (token-based)
# Prevents SSRF-based credential theft attacks
resource "aws_ec2_instance_metadata_defaults" "imdsv2_required" {
  http_tokens                 = "required" # IMDSv2 only
  http_put_response_hop_limit = 1          # Prevent container escape
  http_endpoint               = "enabled"

  # Note: This only affects NEW instances. Existing instances retain their settings.
}