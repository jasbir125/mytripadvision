# Names and tags shared by every resource: <project>-<environment>-<role>

locals {
  name = "${var.project}-${var.environment}"

  tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  image = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"

  # The two Bedrock models the app may call, without duplicates.
  bedrock_model_ids = distinct([var.bedrock_model_id, var.bedrock_final_model_id])

  # Strip the region prefix from cross-region inference profile IDs to get the base model ID.
  # e.g. "apac.amazon.nova-lite-v1:0" -> "amazon.nova-lite-v1:0"
  bedrock_base_model_ids = [
    for id in local.bedrock_model_ids : replace(id, "/^(us|eu|apac|us-gov)\\./", "")
  ]

  # Bedrock region may differ from the deployment region (e.g. infra in ap-southeast-2,
  # Bedrock in us-east-1 to work around an org SCP). Read from extra_environment if set,
  # otherwise use the deployment region.
  bedrock_region = lookup(var.extra_environment, "AWS_REGION", var.aws_region)

  use_https = var.certificate_arn != ""
}
