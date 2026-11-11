# Image registry, database, secrets and logs.

# ---------------------------------------------------------------
# ECR: where the Docker image is stored
# ---------------------------------------------------------------

resource "aws_ecr_repository" "app" {
  name                 = var.name
  image_tag_mutability = "MUTABLE"
  force_delete         = true # lets terraform destroy remove the repository even when it holds images

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------
# RDS PostgreSQL (the pgvector extension is created by the app's Flyway migration)
# ---------------------------------------------------------------

# No special characters, so the password is safe in a JDBC URL without escaping.
resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = "${var.name}-db"
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period    = 7
  auto_minor_version_upgrade = true
  deletion_protection        = var.db_deletion_protection
  skip_final_snapshot        = var.db_skip_final_snapshot
  final_snapshot_identifier  = var.db_skip_final_snapshot ? null : "${var.name}-db-final"
}

# ---------------------------------------------------------------
# Secrets Manager: the app reads these as environment variables
# ---------------------------------------------------------------

resource "aws_secretsmanager_secret" "app" {
  name                    = "${var.name}/app"
  description             = "MyTripAdvision Agent API keys and database password"
  recovery_window_in_days = var.secret_recovery_window_days
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    DB_PASSWORD           = random_password.db.result
    TAVILY_API_KEY        = var.tavily_api_key
    OPENWEATHER_API_KEY   = var.openweather_api_key
    AVIATIONSTACK_API_KEY = var.aviationstack_api_key
  })
}

# ---------------------------------------------------------------
# CloudWatch Logs
# ---------------------------------------------------------------

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
}
