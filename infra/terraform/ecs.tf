# ECS Fargate: runs the Spring Boot container.

resource "aws_ecs_cluster" "main" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

locals {
  image = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"

  # Plain settings. Nothing here is secret. extra_environment (empty on AWS) is for a local emulator such as
  # Floci and may also override a key below, for example DB_URL or AWS_REGION.
  base_environment = {
    PORT            = tostring(var.container_port)
    LLM_PROVIDER    = "bedrock"
    LLM_MODEL       = var.bedrock_model_id
    LLM_FINAL_MODEL = var.bedrock_final_model_id
    AWS_REGION      = var.aws_region
    DB_URL          = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${var.db_name}?sslmode=${var.db_sslmode}"
    DB_USERNAME     = var.db_username
  }
  container_environment = [
    for key, value in merge(local.base_environment, var.extra_environment) : { name = key, value = value }
  ]

  # Read from Secrets Manager when the task starts: "<secret arn>:<json key>::"
  container_secrets = [
    for key in ["DB_PASSWORD", "TAVILY_API_KEY", "OPENWEATHER_API_KEY", "AVIATIONSTACK_API_KEY"] :
    { name = key, valueFrom = "${aws_secretsmanager_secret.app.arn}:${key}::" }
  ]
}

resource "aws_ecs_task_definition" "app" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = "app"
    image     = local.image
    essential = true

    portMappings = [{ containerPort = var.container_port, protocol = "tcp" }]
    environment  = local.container_environment
    secrets      = local.container_secrets

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = var.name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # The app indexes the knowledge base on start-up, so give it time before the load balancer judges it.
  health_check_grace_period_seconds = 240

  # If a new version never becomes healthy, go back to the previous one.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # outbound internet without a NAT gateway; inbound is blocked by the security group
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = var.container_port
  }

  depends_on = [aws_lb_listener.http]
}
