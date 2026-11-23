# ECS Fargate: runs the Spring Boot container.

resource "aws_ecs_cluster" "this" {
  name = "${local.name}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

locals {
  # Determine effective provider — extra_environment wins over the base bedrock default
  effective_provider = lookup(var.extra_environment, "LLM_PROVIDER", "bedrock")

  # Plain settings. extra_environment is merged over them (a key given there wins).
  base_environment = merge(
    {
      PORT        = tostring(var.container_port)
      LLM_PROVIDER = "bedrock"
      AWS_REGION  = var.aws_region
      DB_URL      = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.db_name}?sslmode=${var.db_sslmode}"
      DB_USERNAME = var.db_username
    },
    # Only set Bedrock model IDs when actually using Bedrock — other providers use their own defaults from application.yml
    local.effective_provider == "bedrock" ? {
      LLM_MODEL       = var.bedrock_model_id
      LLM_FINAL_MODEL = var.bedrock_final_model_id
    } : {},
    var.bedrock_endpoint == "" ? {} : { BEDROCK_ENDPOINT = var.bedrock_endpoint },
    var.default_origin_iata == "" ? {} : { DEFAULT_ORIGIN_IATA = var.default_origin_iata },
  )

  container_environment = [
    for key, value in merge(local.base_environment, var.extra_environment) : { name = key, value = value }
  ]

  # Read from Secrets Manager when the task starts: "<secret arn>:<json key>::"
  # DB password comes from the Terraform-managed secret; API keys from the pre-created one.
  container_secrets = concat(
    [{ name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:DB_PASSWORD::" }],
    [for key in ["OPENAI_API_KEY", "GROQ_API_KEY", "TAVILY_API_KEY", "OPENWEATHER_API_KEY", "AVIATIONSTACK_API_KEY"] :
      { name = key, valueFrom = "${data.aws_secretsmanager_secret.api_keys.arn}:${key}::" }
    ]
  )
}

resource "aws_ecs_task_definition" "app" {
  family                   = "${local.name}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

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
  name            = "${local.name}-app"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # ECS Exec lets you open a shell into a running task:
  #   aws ecs execute-command --cluster <cluster> --task <task-id> --container app --interactive --command /bin/sh
  enable_execute_command = true

  # If a new version never becomes healthy, go back to the previous one.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = data.aws_subnets.public.ids
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true # outbound internet without a NAT gateway; inbound gated by the security group
  }
}
