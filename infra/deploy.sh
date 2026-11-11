#!/usr/bin/env bash
# Builds the Docker image, pushes it to ECR and applies the Terraform.
#
#   cd infra && ./deploy.sh
#
# It stops and asks before changing anything in AWS (terraform shows the plan and waits for "yes").
set -euo pipefail

cd "$(dirname "$0")/terraform"
APP_DIR="$(cd ../.. && pwd)"
TAG="${TAG:-$(date +%Y%m%d%H%M%S)}"

echo "1/4 Checking AWS credentials..."
aws sts get-caller-identity --query Arn --output text

echo "2/4 Creating the image registry (ECR)..."
terraform init -input=false
terraform apply -target=aws_ecr_repository.app

REPO="$(terraform output -raw ecr_repository_url)"
REGION="$(aws configure get region || echo us-east-1)"
REGION="${AWS_REGION:-$REGION}"

echo "3/4 Building and pushing ${REPO}:${TAG} (linux/amd64)..."
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${REPO%%/*}"
docker buildx build --platform linux/amd64 -t "${REPO}:${TAG}" --push "$APP_DIR"

echo "4/4 Applying the full stack with image tag ${TAG}..."
terraform apply -var "image_tag=${TAG}"

echo
echo "Done. Open: $(terraform output -raw app_url)"
echo "Logs:       aws logs tail $(terraform output -raw log_group) --follow"
