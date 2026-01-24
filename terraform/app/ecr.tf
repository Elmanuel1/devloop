# =============================================================================
# ECR Repository
# =============================================================================
# Container image registry for TossPaper application
# - One repo per environment (tosspaper-stage, tosspaper-prod)
# - Lifecycle policies: stage=5, prod=30 images retained
# =============================================================================

resource "aws_ecr_repository" "app" {
  name                 = "tosspaper-${var.environment}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }

  tags = {
    Name = "tosspaper-${var.environment}"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = var.environment == "prod" ? "Keep last 30 images" : "Keep last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.environment == "prod" ? 30 : 5
      }
      action = { type = "expire" }
    }]
  })
}
