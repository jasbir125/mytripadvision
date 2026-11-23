# ---------------------------------------------------------------
# Naming
# ---------------------------------------------------------------

variable "project" {
  description = "Project name, the first part of every resource name."
  type        = string
  default     = "mytripadvision"
}

variable "environment" {
  description = "Environment name, the second part of every resource name: prod, staging, floci..."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,15}$", var.environment))
    error_message = "environment must be lowercase letters, digits or dashes, at most 16 characters."
  }
}

variable "aws_region" {
  description = "AWS region for everything, including Bedrock."
  type        = string
  default     = "ap-southeast-2"
}

# ---------------------------------------------------------------
# Network (existing VPC — nothing is created)
# ---------------------------------------------------------------

variable "vpc_id" {
  description = "ID of the existing VPC to deploy into."
  type        = string
}

# ---------------------------------------------------------------
# Access
# ---------------------------------------------------------------

variable "allowed_cidrs" {
  description = "CIDR blocks that may reach the app directly on container_port. Use your own IP to restrict access."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "certificate_arn" {
  description = "Unused without an ALB. Kept so floci.tfvars stays compatible."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------
# Container (ECS Fargate)
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
  description = "X86_64 or ARM64. The Docker image must be built for the same platform."
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
  description = "Fargate memory in MiB."
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

variable "extra_environment" {
  description = "Extra plain environment variables merged into the container definition."
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------
# Bedrock
# ---------------------------------------------------------------

variable "bedrock_model_id" {
  description = "Bedrock model (or cross-region inference profile) for the agents."
  type        = string
  default     = "apac.amazon.nova-lite-v1:0"
}

variable "bedrock_final_model_id" {
  description = "Stronger Bedrock model used only for the final answer."
  type        = string
  default     = "apac.amazon.nova-pro-v1:0"
}

variable "bedrock_endpoint" {
  description = "Empty on AWS. For a local emulator the URL the app sends Bedrock calls to."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------
# Database (RDS PostgreSQL)
# ---------------------------------------------------------------

variable "db_name" {
  description = "Database name."
  type        = string
  default     = "mytripadvision"
}

variable "db_username" {
  description = "Database user."
  type        = string
  default     = "mytripadvision"
}

variable "db_instance_class" {
  description = "RDS instance class. db.t3.micro is free tier eligible."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "Storage in GiB."
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "A standby copy in a second AZ."
  type        = bool
  default     = false
}

variable "db_deletion_protection" {
  description = "Block terraform destroy until turned off."
  type        = bool
  default     = false
}

variable "db_skip_final_snapshot" {
  description = "Set to false to take a snapshot when the database is destroyed."
  type        = bool
  default     = true
}

variable "db_sslmode" {
  description = "JDBC sslmode: require for RDS, disable for local emulators."
  type        = string
  default     = "require"

  validation {
    condition     = contains(["disable", "prefer", "require", "verify-ca", "verify-full"], var.db_sslmode)
    error_message = "db_sslmode must be disable, prefer, require, verify-ca or verify-full."
  }
}

# ---------------------------------------------------------------
# API key rotation window
# ---------------------------------------------------------------

variable "default_origin_iata" {
  description = "Departure airport IATA code used when the user names no origin."
  type        = string
  default     = ""
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret stays recoverable (0 = delete immediately)."
  type        = number
  default     = 7
}
