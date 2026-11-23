output "app_url_note" {
  description = "The app has no load balancer. Find the task's public IP with the command below, then open http://<ip>:8000"
  value       = "aws ecs list-tasks --cluster ${aws_ecs_cluster.this.name} --query 'taskArns[0]' --output text | xargs -I{} aws ecs describe-tasks --cluster ${aws_ecs_cluster.this.name} --tasks {} --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text | xargs -I{} aws ec2 describe-network-interfaces --network-interface-ids {} --query 'NetworkInterfaces[0].Association.PublicIp' --output text"
}

output "ecr_repository_url" {
  description = "Where deploy.sh pushes the Docker image."
  value       = aws_ecr_repository.this.repository_url
}

output "ecs_cluster" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service" {
  description = "ECS service name."
  value       = aws_ecs_service.app.name
}

output "log_group" {
  description = "Follow the logs: aws logs tail <this> --follow"
  value       = aws_cloudwatch_log_group.app.name
}

output "database_endpoint" {
  description = "Private address of the database (reachable only from the app)."
  value       = aws_db_instance.this.address
}

output "bedrock_models" {
  description = "Models the app may call."
  value       = local.bedrock_model_ids
}
