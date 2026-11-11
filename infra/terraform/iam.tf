# Two roles:
#   execution role: used by ECS itself to pull the image, write logs and read the secret
#   task role:      used by the app. It may call ONLY the two configured Bedrock models.

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

resource "aws_iam_role" "execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-app-secret"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [aws_secretsmanager_secret.app.arn]
    }]
  })
}

# ---------------------------------------------------------------
# Task role: Bedrock
# ---------------------------------------------------------------

locals {
  # The two models the app is configured to use, without duplicates.
  bedrock_model_ids = distinct([var.bedrock_model_id, var.bedrock_final_model_id])

  # A cross-region inference profile id looks like "us.amazon.nova-lite-v1:0".
  # The model behind it is "amazon.nova-lite-v1:0".
  bedrock_base_model_ids = [
    for id in local.bedrock_model_ids : replace(id, "/^(us|eu|apac|us-gov)\\./", "")
  ]

  bedrock_resources = concat(
    # the model itself; the region is a wildcard because an inference profile may route to other regions
    [for id in local.bedrock_base_model_ids :
    "arn:${data.aws_partition.current.partition}:bedrock:*::foundation-model/${id}"],
    # the inference profile (only exists for ids with a region prefix; harmless otherwise)
    [for id in local.bedrock_model_ids :
    "arn:${data.aws_partition.current.partition}:bedrock:${var.aws_region}:${data.aws_caller_identity.current.account_id}:inference-profile/${id}"],
  )
}

resource "aws_iam_role" "task" {
  name               = "${var.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "task_bedrock" {
  name = "invoke-bedrock-models"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      Resource = local.bedrock_resources
    }]
  })
}
