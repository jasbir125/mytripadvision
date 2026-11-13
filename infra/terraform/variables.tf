# ---------------------------------------------------------------
# General
# ---------------------------------------------------------------

variable "aws_region" {
  description = "AWS region for everything, including Bedrock."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "Prefix for resource names."
  type        = string
  default     = "mytripadvision"
}

variable "allowed_cidrs" {
  description = <<-EOT
    Who may open the web page (CIDR blocks), for example ["203.0.113.10/32"] for just your IP.
    There is NO default on purpose: the app has no login, and every trip request costs Bedrock and API money.
    Use ["0.0.0.0/0"] only when you accept that anyone on the internet can use it.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.allowed_cidrs) > 0
    error_message = "Give at least one CIDR block in allowed_cidrs."
  }
}

variable "certificate_arn" {
  description = "Optional ACM certificate ARN. When set, the load balancer serves HTTPS and redirects HTTP to it."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------
# Network
# ---------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Two public and two private subnets are carved out of it."
  type        = string
  default     = "10.20.0.0/16"
}

# ---------------------------------------------------------------
# Container
# ---------------------------------------------------------------

variable "image_tag" {
  description = "Tag of the image in ECR to run. deploy.sh sets it to a timestamp."
  type        = string
  default     = "latest"
}

variable "container_port" {
  description = "Port the Spring Boot app listens on."
  type        = number
  default     = 8000
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64. The Docker image must be built for the same platform (deploy.sh builds linux/amd64)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "task_cpu" {
  description = "Fargate CPU units (1024 = 1 vCPU)."
  type        = number
  default     = 1024
}

variable "task_memory" {
  description = "Fargate memory in MiB. The embedding model needs room, so do not go below 2048."
  type        = number
  default     = 2048
}

variable "desired_count" {
  description = "How many app tasks to run."
  type        = number
  default     = 1
}

variable "log_retention_days" {
  description = "How long CloudWatch keeps the app logs."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------
# Bedrock
# ---------------------------------------------------------------

variable "bedrock_model_id" {
  description = <<-EOT
    Bedrock model (or cross-region inference profile) for the agents, for example us.amazon.nova-lite-v1:0.
    Check what your account can use:  aws bedrock list-inference-profiles --region <region>
    Anthropic models need the one-time use-case form in the Bedrock console; Amazon Nova works out of the box.
  EOT
  type        = string
  default     = "us.amazon.nova-lite-v1:0"
}

variable "bedrock_final_model_id" {
  description = "Stronger Bedrock model used only for the final answer."
  type        = string
  default     = "us.amazon.nova-pro-v1:0"
}

# ---------------------------------------------------------------
# Database
# ---------------------------------------------------------------

variable "db_name" {
  type    = string
  default = "mytripadvision"
}

variable "db_username" {
  type    = string
  default = "mytripadvision"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Storage in GiB."
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "A standby copy in a second zone. Doubles the database cost."
  type        = bool
  default     = false
}

variable "db_deletion_protection" {
  description = "Set to true for anything you care about. It blocks terraform destroy until you turn it off."
  type        = bool
  default     = false
}

variable "db_sslmode" {
  description = "JDBC sslmode for the database connection. RDS needs \"require\"; a local emulator's PostgreSQL has no TLS, so use \"disable\" there."
  type        = string
  default     = "require"
}

variable "extra_environment" {
  description = <<-EOT
    Extra plain environment variables for the app container. Empty on AWS. For a local emulator such as Floci:
    { BEDROCK_ENDPOINT = "http://host.docker.internal:4566", AWS_ACCESS_KEY_ID = "test", AWS_SECRET_ACCESS_KEY = "test" }
  EOT
  type        = map(string)
  default     = {}
}

variable "db_skip_final_snapshot" {
  description = "Set to false to take a snapshot when the database is destroyed."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------
# API keys (stored in Secrets Manager, never in the task definition)
# ---------------------------------------------------------------

variable "tavily_api_key" {
  type      = string
  sensitive = true
}

variable "openweather_api_key" {
  type      = string
  sensitive = true
}

variable "aviationstack_api_key" {
  type      = string
  sensitive = true
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret stays recoverable (0 = delete at once, handy while testing)."
  type        = number
  default     = 7
}
