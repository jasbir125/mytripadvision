output "app_url" {
  description = "Open this in the browser."
  value       = "${local.use_https ? "https" : "http"}://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  description = "Where deploy.sh pushes the Docker image."
  value       = aws_ecr_repository.app.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "log_group" {
  description = "Follow the logs: aws logs tail <this> --follow"
  value       = aws_cloudwatch_log_group.app.name
}

output "database_endpoint" {
  description = "Private address of the database (reachable only from the app)."
  value       = aws_db_instance.main.address
}

output "bedrock_models" {
  description = "Models the app may call."
  value       = local.bedrock_model_ids
}
