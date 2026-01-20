# =============================================================================
# EC2 Instance - Application Server
# =============================================================================
# ARM-based instance (t4g) for cost efficiency
# Stage uses Spot instances, Prod uses On-Demand
# Deployed in PUBLIC subnet with direct internet access (no NAT Gateway)
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

# -----------------------------------------------------------------------------
# EC2 Instance (On-Demand for prod, Spot for stage)
# -----------------------------------------------------------------------------
resource "aws_instance" "app" {
  ami           = data.aws_ami.amazon_linux_2023.id
  instance_type = local.config.instance_type

  # Network - PUBLIC subnet with public IP
  subnet_id                   = var.public_subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.app.id]
  associate_public_ip_address = true # Direct internet access

  # IAM
  iam_instance_profile = aws_iam_instance_profile.app.name

  # Storage
  root_block_device {
    volume_type           = "gp3"
    volume_size           = local.config.ebs_volume_size
    encrypted             = true
    delete_on_termination = true

    tags = {
      Name = "${local.name_prefix}-root-volume"
    }
  }

  # SSH key (optional)
  key_name = var.ssh_key_name != "" ? var.ssh_key_name : null

  # Spot instance (stage only)
  instance_market_options {
    market_type = local.config.use_spot ? "spot" : null

    dynamic "spot_options" {
      for_each = local.config.use_spot ? [1] : []
      content {
        instance_interruption_behavior = "stop"
        spot_instance_type             = "persistent"
      }
    }
  }

  # User data - install dependencies and configure app
  user_data_base64 = base64encode(templatefile("${path.module}/user-data.sh.tpl", {
    environment    = var.environment
    aws_region     = var.aws_region
    s3_bucket_name = aws_s3_bucket.attachments.id
  }))

  # Metadata options (IMDSv2 required)
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required" # Require IMDSv2
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "enabled"
  }

  # Monitoring
  monitoring = !local.is_lower_env

  tags = {
    Name = "${local.name_prefix}-app"
  }

  lifecycle {
    # Prevent accidental termination (set to false temporarily if destroy needed)
    prevent_destroy = false # TODO: Re-enable after instance replacement

    # Ignore AMI changes to prevent unintended replacements
    ignore_changes = [ami]
  }
}

# -----------------------------------------------------------------------------
# Elastic IP - Static public IP for consistent addressing
# -----------------------------------------------------------------------------
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"

  tags = {
    Name = "${local.name_prefix}-app-eip"
  }
}
