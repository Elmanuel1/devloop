# =============================================================================
# Auto Scaling Group
# =============================================================================
# ASG manages EC2 instances with automatic scaling based on:
# - CPU utilization (target tracking at 70%)
# - Memory utilization (target tracking at 70%, requires CloudWatch agent)
# - SQS queue depth (step scaling for queue backlog)
# =============================================================================

# -----------------------------------------------------------------------------
# Launch Template
# -----------------------------------------------------------------------------
resource "aws_launch_template" "app" {
  name        = "${local.name_prefix}-lt"
  description = "Launch template for ${local.name_prefix} application"

  image_id      = data.aws_ami.amazon_linux_2023.id
  instance_type = local.config.instance_type

  # IAM
  iam_instance_profile {
    name = aws_iam_instance_profile.app.name
  }

  # Network
  network_interfaces {
    associate_public_ip_address = true
    security_groups             = [aws_security_group.app.id]
    delete_on_termination       = true
  }

  # Storage
  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_type           = "gp3"
      volume_size           = local.config.ebs_volume_size
      encrypted             = true
      delete_on_termination = true
    }
  }

  # Spot instances for stage
  dynamic "instance_market_options" {
    for_each = local.config.use_spot ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        spot_instance_type             = "one-time"
        instance_interruption_behavior = "terminate"
      }
    }
  }

  # User data
  user_data = base64encode(templatefile("${path.module}/user-data.sh.tpl", {
    environment    = var.environment
    aws_region     = var.aws_region
    s3_bucket_name = aws_s3_bucket.attachments.id
    efs_id         = aws_efs_file_system.app_files.id
    vpc_cidr       = data.aws_vpc.selected.cidr_block
  }))

  # Metadata options (IMDSv2 required)
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "enabled"
  }

  # Monitoring
  monitoring {
    enabled = !local.is_lower_env
  }

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name = "${local.name_prefix}-app"
    }
  }

  tag_specifications {
    resource_type = "volume"
    tags = {
      Name = "${local.name_prefix}-root-volume"
    }
  }

  tags = {
    Name = "${local.name_prefix}-lt"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Auto Scaling Group
# -----------------------------------------------------------------------------
resource "aws_autoscaling_group" "app" {
  name                = "${local.name_prefix}-asg"
  vpc_zone_identifier = var.public_subnet_ids
  target_group_arns   = [aws_lb_target_group.app.arn]

  min_size         = var.asg_min_size
  max_size         = var.asg_max_size
  desired_capacity = var.asg_min_size

  # Distribute instances across AZs for high availability
  availability_zone_distribution {
    capacity_distribution_strategy = "balanced-best-effort"
  }

  # Health check - ELB for prod (replace unhealthy), EC2 for stage (debug friendly)
  health_check_type         = local.config.use_elb_health_check ? "ELB" : "EC2"
  health_check_grace_period = 300

  # Instance refresh for rolling updates
  # min_healthy_percentage = 100 ensures at least 1 instance stays healthy
  # even when min_size = 1 (launches new instance before terminating old)
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 100
      instance_warmup        = 300
    }
  }

  # Launch template
  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }

  # Termination policy - prefer older instances
  termination_policies = ["OldestInstance"]

  # Warm pool (optional - keeps instances in stopped state for faster scaling)
  # warm_pool {
  #   pool_state                  = "Stopped"
  #   min_size                    = 0
  #   max_group_prepared_capacity = 1
  # }

  # Tags propagated to instances
  tag {
    key                 = "Name"
    value               = "${local.name_prefix}-app"
    propagate_at_launch = true
  }

  tag {
    key                 = "Environment"
    value               = var.environment
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
    ignore_changes        = [desired_capacity]
  }
}

# -----------------------------------------------------------------------------
# Scaling Policy: CPU Target Tracking (70%)
# -----------------------------------------------------------------------------
resource "aws_autoscaling_policy" "cpu_target_tracking" {
  name                   = "${local.name_prefix}-cpu-target-tracking"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "TargetTrackingScaling"

  target_tracking_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
    target_value     = 70.0
    disable_scale_in = false
  }
}

# -----------------------------------------------------------------------------
# SQS Scaling: CloudWatch Metric for Combined Queue Depth
# -----------------------------------------------------------------------------
# Math expression: sum of all 6 queue depths
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "sqs_backlog_high" {
  alarm_name          = "${local.name_prefix}-sqs-backlog-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 100
  alarm_description   = "Combined SQS queue depth > 100 messages"

  metric_query {
    id          = "total"
    expression  = "m1 + m2 + m3 + m4 + m5 + m6"
    label       = "Total Queue Depth"
    return_data = true
  }

  metric_query {
    id = "m1"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.ai_process.name
      }
    }
  }

  metric_query {
    id = "m2"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.email_local_uploads.name
      }
    }
  }

  metric_query {
    id = "m3"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.vector_store_ingestion.name
      }
    }
  }

  metric_query {
    id = "m4"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.document_approved.name
      }
    }
  }

  metric_query {
    id = "m5"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.quickbooks_events.name
      }
    }
  }

  metric_query {
    id = "m6"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.integration_push.name
      }
    }
  }

  alarm_actions = [aws_autoscaling_policy.sqs_scale_out.arn]
  ok_actions    = []

  tags = {
    Name = "${local.name_prefix}-sqs-backlog-high-alarm"
  }
}

resource "aws_cloudwatch_metric_alarm" "sqs_backlog_low" {
  alarm_name          = "${local.name_prefix}-sqs-backlog-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 5 # 5 minutes of low queue depth before scale-in
  threshold           = 10
  alarm_description   = "Combined SQS queue depth < 10 messages for 5 minutes"

  metric_query {
    id          = "total"
    expression  = "m1 + m2 + m3 + m4 + m5 + m6"
    label       = "Total Queue Depth"
    return_data = true
  }

  metric_query {
    id = "m1"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.ai_process.name
      }
    }
  }

  metric_query {
    id = "m2"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.email_local_uploads.name
      }
    }
  }

  metric_query {
    id = "m3"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.vector_store_ingestion.name
      }
    }
  }

  metric_query {
    id = "m4"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.document_approved.name
      }
    }
  }

  metric_query {
    id = "m5"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.quickbooks_events.name
      }
    }
  }

  metric_query {
    id = "m6"
    metric {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      period      = 60
      stat        = "Average"
      dimensions = {
        QueueName = aws_sqs_queue.integration_push.name
      }
    }
  }

  alarm_actions = [aws_autoscaling_policy.sqs_scale_in.arn]
  ok_actions    = []

  tags = {
    Name = "${local.name_prefix}-sqs-backlog-low-alarm"
  }
}

# -----------------------------------------------------------------------------
# SQS Scale-Out Policy (Step Scaling)
# -----------------------------------------------------------------------------
resource "aws_autoscaling_policy" "sqs_scale_out" {
  name                   = "${local.name_prefix}-sqs-scale-out"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "StepScaling"
  adjustment_type        = "ChangeInCapacity"

  step_adjustment {
    scaling_adjustment          = 1
    metric_interval_lower_bound = 0
  }

  # Cooldown
  estimated_instance_warmup = 300
}

# -----------------------------------------------------------------------------
# SQS Scale-In Policy (Step Scaling)
# -----------------------------------------------------------------------------
resource "aws_autoscaling_policy" "sqs_scale_in" {
  name                   = "${local.name_prefix}-sqs-scale-in"
  autoscaling_group_name = aws_autoscaling_group.app.name
  policy_type            = "StepScaling"
  adjustment_type        = "ChangeInCapacity"

  step_adjustment {
    scaling_adjustment          = -1
    metric_interval_upper_bound = 0
  }

  # Cooldown
  estimated_instance_warmup = 300
}
