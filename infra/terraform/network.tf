# Using existing VPC and subnets — no new network resources are created.
# VPC:     vpc-05a030c65116d0cee  (default VPC, ap-southeast-2)
# Subnets: 3 public subnets across ap-southeast-2a/b/c (MapPublicIpOnLaunch=true)
# IGW:     igw-0c37f2bb3ffa1c1b2  (already attached)

data "aws_vpc" "this" {
  id = var.vpc_id
}

data "aws_subnets" "public" {
  filter {
    name   = "vpc-id"
    values = [var.vpc_id]
  }
}
