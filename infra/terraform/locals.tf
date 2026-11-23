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

  use_https = var.certificate_arn != ""
}
