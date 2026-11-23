# Two roles:
#   ecs_execution: used by ECS itself to pull the image, write logs and read the secret
#   ecs_task:      used by the app. It may call ONLY the configured Bedrock models.

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------
# Execution role
# ---------------------------------------------------------------

resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name}-ecs-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "${local.name}-secrets-read"
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = [
        data.aws_secretsmanager_secret.api_keys.arn,
        aws_secretsmanager_secret.db.arn,
      ]
    }]
  })
}

# ---------------------------------------------------------------
# Task role: Bedrock only
# ---------------------------------------------------------------

locals {
  bedrock_resources = concat(
    # the base model ARN — region wildcard so the cross-region routing works
    [for id in local.bedrock_base_model_ids :
    "arn:${data.aws_partition.current.partition}:bedrock:*::foundation-model/${id}"],
    # the inference profile ARN — must match the region the app actually calls Bedrock in
    [for id in local.bedrock_model_ids :
    "arn:${data.aws_partition.current.partition}:bedrock:${local.bedrock_region}:${data.aws_caller_identity.current.account_id}:inference-profile/${id}"],
  )
}

resource "aws_iam_role" "ecs_task" {
  name               = "${local.name}-ecs-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "ecs_task_bedrock" {
  name = "${local.name}-bedrock-invoke"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      Resource = local.bedrock_resources
    }]
  })
}

# ECS Exec: lets you open a shell into a running task without SSH.
# aws ecs execute-command --cluster <cluster> --task <id> --container app --interactive --command /bin/sh
resource "aws_iam_role_policy" "ecs_task_exec_command" {
  name = "${local.name}-ecs-exec"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel"
      ]
      Resource = "*"
    }]
  })
}
