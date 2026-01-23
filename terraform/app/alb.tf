# =============================================================================
# Application Load Balancer
# =============================================================================
# ALB receives traffic from Cloudflare and distributes to ASG instances
# - HTTPS only (Cloudflare handles HTTP -> HTTPS redirect)
# - Health check on /actuator/health via HTTPS
# =============================================================================

# -----------------------------------------------------------------------------
# ACM Certificate (Cloudflare Origin) - fetched by domain
# -----------------------------------------------------------------------------
data "aws_acm_certificate" "cloudflare_origin" {
  domain      = "*.tosspaper.com"
  statuses    = ["ISSUED"]
  most_recent = true
}

# -----------------------------------------------------------------------------
# ALB Security Group - Allow Cloudflare IPs only
# -----------------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb-sg"
  description = "Security group for ALB (Cloudflare only)"
  vpc_id      = var.vpc_id

  # Ingress: HTTPS from Cloudflare only
  ingress {
    description = "HTTPS from Cloudflare"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = local.cloudflare_ipv4_cidrs
  }

  # Egress: Allow traffic to targets (EC2 instances in VPC)
  egress {
    description = "To targets"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-alb-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Application Load Balancer
# -----------------------------------------------------------------------------
resource "aws_lb" "app" {
  name               = "${local.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  # Enable deletion protection in production
  enable_deletion_protection = !local.is_lower_env

  # Drop invalid HTTP headers (security best practice)
  drop_invalid_header_fields = true

  # Access logs (optional - uncomment to enable)
  # access_logs {
  #   bucket  = aws_s3_bucket.alb_logs.id
  #   prefix  = "alb"
  #   enabled = true
  # }

  tags = {
    Name = "${local.name_prefix}-alb"
  }
}

# -----------------------------------------------------------------------------
# Target Group - HTTPS to EC2 instances (nginx on 443)
# -----------------------------------------------------------------------------
resource "aws_lb_target_group" "app" {
  name     = "${local.name_prefix}-tg"
  port     = 443
  protocol = "HTTPS"
  vpc_id   = var.vpc_id

  # Load balancing algorithm - LOR routes to instance with fewest in-flight requests
  # Better for varying request durations (e.g., AI processing jobs)
  load_balancing_algorithm_type = "least_outstanding_requests"

  # Health check configuration
  health_check {
    enabled             = true
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 10
    interval            = 30
    path                = "/actuator/health"
    port                = "443"
    protocol            = "HTTPS"
    matcher             = "200"
  }

  # Deregistration delay - time to drain connections before removing instance
  deregistration_delay = 60

  # Stickiness (disabled - app is stateless)
  stickiness {
    type    = "lb_cookie"
    enabled = false
  }

  tags = {
    Name = "${local.name_prefix}-tg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# HTTPS Listener
# -----------------------------------------------------------------------------
# Uses Cloudflare origin certificate (stored in ACM)
# Cloudflare terminates public TLS, ALB uses origin cert for Cloudflare->ALB
# -----------------------------------------------------------------------------
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = data.aws_acm_certificate.cloudflare_origin.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  tags = {
    Name = "${local.name_prefix}-https-listener"
  }
}
