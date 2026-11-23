#!/usr/bin/env bash
# Prints the current public URL of the running ECS task.
# Usage: bash infra/get-app-url.sh
set -euo pipefail

CLUSTER="mytripadvision-prod-cluster"
REGION="ap-southeast-2"

TASK=$(aws ecs list-tasks --cluster "$CLUSTER" --region "$REGION" \
  --query 'taskArns[0]' --output text)

if [ "$TASK" = "None" ] || [ -z "$TASK" ]; then
  echo "No running task found in cluster $CLUSTER"
  exit 1
fi

ENI=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK" --region "$REGION" \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' --output text)

IP=$(aws ec2 describe-network-interfaces --network-interface-ids "$ENI" --region "$REGION" \
  --query 'NetworkInterfaces[0].Association.PublicIp' --output text)

echo "http://$IP:8000"
